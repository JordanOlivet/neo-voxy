package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;

public class RocksDBStorageBackend extends StorageBackend {
    // rocksdbjni 8.10.0 exposes neither RepairDB nor DB::Resume, so a corrupt SST
    // cannot be repaired or selectively dropped in-process. The only recovery the
    // binding allows is to close the DB, move the whole store aside (or destroy it)
    // and reopen a fresh one. The cached LOD data is regenerable, so this is an
    // acceptable - if blunt - way to keep the game running instead of spamming the
    // corruption exception on every save and taking the client/server down with it.
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    private final String path;

    // Reassigned by openDb()/recover(): not final so the store can be rebuilt.
    private RocksDB db;
    private ColumnFamilyHandle worldSections;
    private ColumnFamilyHandle idMappings;
    private ReadOptions sectionReadOps;
    private WriteOptions sectionWriteOps;

    // NOTE: closes in order
    private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();

    // Normal ops take the read lock; recovery (and close) take the write lock so the
    // store can be torn down and rebuilt with no in-flight access. dbGeneration is the
    // single-flight token: a thread that observed generation N before failing only
    // triggers a recovery if no one else already rebuilt the store (generation moved on).
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private volatile long dbGeneration = 0;
    private volatile boolean disabled = false;
    private int recoveryAttempts = 0; // guarded by the write lock

    public RocksDBStorageBackend(String path) {
        this.path = path;
        RocksDB.loadLibrary();
        try {
            this.openDb();
        } catch (RocksDBException e) {
            // Startup redundancy: if the store is already corrupt on disk we can't open
            // it at all, so back it up and start fresh rather than failing the world load.
            if (isCorruption(e)) {
                Logger.error("Voxy RocksDB store at " + path + " is corrupt on open; backing it up and creating a fresh store", e);
                this.backupOrDestroyStore();
                try {
                    this.openDb();
                } catch (RocksDBException e2) {
                    throw new RuntimeException("Failed to open Voxy RocksDB store even after corruption backup", e2);
                }
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private void openDb() throws RocksDBException {
        this.closeList.clear();

        // Each column family owns its own ColumnFamilyOptions instance so they can be
        // tuned independently and so closing them follows a clean 1:1 ownership model.
        //
        // - DEFAULT: required by RocksDB but unused by voxy. Keep minimal.
        // - world_sections: the bulk of the data. Already-compressed payloads, lots of
        //   point lookups by section key -> NO_COMPRESSION + point-lookup optimisation
        //   + block cache + bloom filter.
        // - id_mappings: a small dense table of block-state-id -> serialised data.
        //   ZSTD compression + small-db optimisation.
        final ColumnFamilyOptions cfDefaultOpts = new ColumnFamilyOptions()
                .optimizeForSmallDb();

        final ColumnFamilyOptions cfIdMappingsOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                .optimizeForSmallDb();

        final ColumnFamilyOptions cfWorldSecOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.NO_COMPRESSION)
                .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                .setLevelCompactionDynamicLevelBytes(true)
                .optimizeForPointLookup(128);

        var bCache = new HyperClockCache(128 * 1024L * 1024L, 0, 4, false);
        var filter = new BloomFilter(10);
        cfWorldSecOpts.setTableFormatConfig(new BlockBasedTableConfig()
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setBlockCache(bCache)
                .setDataBlockHashTableUtilRatio(0.75)
                // .setIndexType(IndexType.kHashSearch)//Maybe?
                .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
                .setFilterPolicy(filter));

        final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfDefaultOpts),
                new ColumnFamilyDescriptor("world_sections".getBytes(), cfWorldSecOpts),
                new ColumnFamilyDescriptor("id_mappings".getBytes(), cfIdMappingsOpts));

        final DBOptions options = new DBOptions()
                // .setUnorderedWrite(true)
                .setAvoidUnnecessaryBlockingIO(true)
                .setIncreaseParallelism(2)
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxTotalWalSize(1024 * 1024 * 128);// 128 mb max WAL size

        List<ColumnFamilyHandle> handles = new ArrayList<>();

        boolean opened = false;
        try {
            this.db = RocksDB.open(options,
                    this.path, cfDescriptors,
                    handles);

            this.sectionReadOps = new ReadOptions();
            this.sectionWriteOps = new WriteOptions();

            // The db itself is NOT in the closeList: it must be closed last, via
            // closeE(), after every dependent native handle is released. Having it
            // closed mid-list leaked native memory on every world unload.
            this.closeList.add(options);
            this.closeList.add(cfDefaultOpts);
            this.closeList.add(cfIdMappingsOpts);
            this.closeList.add(cfWorldSecOpts);
            this.closeList.add(this.sectionReadOps);
            this.closeList.add(this.sectionWriteOps);
            this.closeList.add(filter);
            this.closeList.add(bCache);
            this.closeList.addAll(handles);

            this.worldSections = handles.get(1);
            this.idMappings = handles.get(2);

            this.db.flushWal(true);
            opened = true;
        } finally {
            if (!opened) {
                // Open failed (e.g. corruption): release everything we allocated so the
                // caller can retry on a clean slate. The db handle is closed too if it
                // managed to open before a later step threw.
                for (var handle : handles) {
                    handle.close();
                }
                cfDefaultOpts.close();
                cfIdMappingsOpts.close();
                cfWorldSecOpts.close();
                filter.close();
                bCache.close();
                options.close();
                if (this.sectionReadOps != null) {
                    this.sectionReadOps.close();
                    this.sectionReadOps = null;
                }
                if (this.sectionWriteOps != null) {
                    this.sectionWriteOps.close();
                    this.sectionWriteOps = null;
                }
                if (this.db != null) {
                    this.db.close();
                    this.db = null;
                }
                this.closeList.clear();
            }
        }
    }

    // Closes every native handle without flushing (a corrupt / bg-errored DB throws on
    // flush) so the store directory can be moved or destroyed afterwards.
    private void closeQuietly() {
        for (var ref : this.closeList) {
            try {
                ref.close();
            } catch (Exception ignored) {
            }
        }
        this.closeList.clear();
        this.worldSections = null;
        this.idMappings = null;
        this.sectionReadOps = null;
        this.sectionWriteOps = null;
        if (this.db != null) {
            try {
                this.db.close();
            } catch (Exception ignored) {
            }
            this.db = null;
        }
    }

    // Moves the corrupt store aside for later inspection, or destroys it if it can't be
    // moved (e.g. lingering file locks on Windows), so a fresh store can be created.
    // The DB must already be closed before calling this.
    private void backupOrDestroyStore() {
        Path src = Path.of(this.path);
        if (!Files.exists(src)) {
            return;
        }
        Path dst = Path.of(this.path + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(src, dst);
            Logger.warn("Backed up corrupt Voxy store to " + dst + " - it can be deleted once you no longer need it for diagnostics");
            return;
        } catch (IOException e) {
            Logger.warn("Could not back up corrupt Voxy store at " + src + " (" + e.getMessage() + "); attempting to destroy it instead");
        }
        try (Options destroyOpts = new Options()) {
            RocksDB.destroyDB(this.path, destroyOpts);
        } catch (Exception e) {
            Logger.error("Failed to destroy corrupt Voxy store at " + this.path + "; the next open will likely fail", e);
        }
    }

    // Called (without the read lock held) when an operation hits a corruption error.
    // observedGeneration is the dbGeneration seen before the failing call: if it no
    // longer matches, another thread already rebuilt the store and this is a no-op.
    private void recover(long observedGeneration) {
        this.rwLock.writeLock().lock();
        try {
            if (this.disabled) {
                return;
            }
            if (observedGeneration != this.dbGeneration) {
                return; // already recovered by another thread since this op started
            }
            if (this.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
                this.disabled = true;
                this.closeQuietly();
                Logger.error("Voxy RocksDB store at " + this.path + " hit corruption " + this.recoveryAttempts +
                        " times; disabling LOD persistence for this session. LOD will still render but won't be saved - check your disk health.");
                return;
            }
            this.recoveryAttempts++;
            Logger.error("Voxy RocksDB store at " + this.path + " is corrupted (recovery attempt " + this.recoveryAttempts +
                    "/" + MAX_RECOVERY_ATTEMPTS + "); closing it, backing up the corrupt store and rebuilding from scratch. Cached LOD will be regenerated as you explore.");
            this.closeQuietly();
            try {
                this.backupOrDestroyStore();
                this.openDb();
                this.dbGeneration++;
                Logger.info("Voxy RocksDB store rebuilt successfully at " + this.path);
            } catch (Exception e) {
                this.disabled = true;
                this.closeQuietly();
                Logger.error("Voxy RocksDB store recovery failed; disabling LOD persistence for this session", e);
            }
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }

    private static boolean isCorruption(RocksDBException e) {
        Status status = e.getStatus();
        if (status != null && status.getCode() == Status.Code.Corruption) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("checksum mismatch") || msg.contains("Corruption"));
    }

    @Override
    public void iterateStoredSectionPositions(LongConsumer consumer) {
        if (this.disabled) {
            return;
        }
        long gen = this.dbGeneration;
        ByteBuffer keyBuff = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        long keyBuffPtr = UnsafeUtil.memAddress(keyBuff);
        this.rwLock.readLock().lock();
        try {
            var iter = this.db.newIterator(this.worldSections, this.sectionReadOps);
            try {
                iter.seekToFirst();
                while (iter.isValid()) {
                    iter.key(keyBuff);
                    long key = Long.reverseBytes(UnsafeUtil.memGetLong(keyBuffPtr));
                    consumer.accept(key);
                    iter.next();
                }
                // isValid() goes false on both end-of-data and error; status() surfaces
                // a corruption that was swallowed during iteration.
                iter.status();
            } finally {
                iter.close();
            }
            return;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        // Corruption: rebuild the store. Any positions already emitted now resolve to
        // missing data on load and regenerate; the rest of the scan is abandoned.
        recover(gen);
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        if (this.disabled) {
            return null;
        }
        long gen = this.dbGeneration;
        this.rwLock.readLock().lock();
        try {
            byte[] data = this.db.get(this.worldSections, this.sectionReadOps, longToBytes(key));
            if (data == null) {
                return null;
            }

            if (data.length > scratch.size) {
                // If the data is too big, we log an error because this should theoretically
                // never happen unless something is very wrong
                // or the scratch buffer size heuristics are broken
                System.err.println("Data size too big for scratch buffer: " + data.length + " vs " + scratch.size);
                return null;
            }

            UnsafeUtil.memcpy(data, scratch.address);

            return scratch.subSize(data.length);
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        // Corruption: rebuild the store and treat this section as missing (it regenerates).
        recover(gen);
        return null;
    }

    // TODO: FIXME, use the ByteBuffer variant
    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        if (this.disabled) {
            return;
        }
        long gen = this.dbGeneration;
        ByteBuffer keyBuff = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        UnsafeUtil.memPutLong(UnsafeUtil.memAddress(keyBuff), Long.reverseBytes(key));
        this.rwLock.readLock().lock();
        try {
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
            return;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        recover(gen);
    }

    @Override
    public void deleteSectionData(long key) {
        if (this.disabled) {
            return;
        }
        long gen = this.dbGeneration;
        this.rwLock.readLock().lock();
        try {
            this.db.delete(this.worldSections, longToBytes(key));
            return;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        recover(gen);
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        if (this.disabled) {
            return;
        }
        long gen = this.dbGeneration;
        this.rwLock.readLock().lock();
        try {
            var buffer = new byte[data.remaining()];
            data.get(buffer);
            data.rewind();
            this.db.put(this.idMappings, intToBytes(id), buffer);
            return;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        recover(gen);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        if (this.disabled) {
            return new Int2ObjectOpenHashMap<>();
        }
        long gen = this.dbGeneration;
        var out = new Int2ObjectOpenHashMap<byte[]>();
        this.rwLock.readLock().lock();
        try (var iterator = this.db.newIterator(this.idMappings)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                out.put(bytesToInt(iterator.key()), iterator.value());
            }
            // isValid() goes false on both end-of-data and error; status() surfaces
            // a corruption that was swallowed during iteration.
            iterator.status();
            return out;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        // Corruption: rebuild the store (now empty) and return no mappings, which keeps
        // the result consistent with the wiped store instead of leaking a partial map.
        recover(gen);
        return new Int2ObjectOpenHashMap<>();
    }

    @Override
    public void flush() {
        if (this.disabled) {
            return;
        }
        long gen = this.dbGeneration;
        this.rwLock.readLock().lock();
        try {
            this.db.flushWal(true);
            return;
        } catch (RocksDBException e) {
            if (!isCorruption(e)) {
                throw new RuntimeException(e);
            }
        } finally {
            this.rwLock.readLock().unlock();
        }
        recover(gen);
    }

    @Override
    public void close() {
        this.rwLock.writeLock().lock();
        try {
            if (this.disabled || this.db == null) {
                this.closeQuietly();
                return;
            }
            try {
                this.db.flushWal(true);
            } catch (RocksDBException e) {
                Logger.warn("Failed flushing Voxy RocksDB on close", e);
            }
            this.closeList.forEach(AbstractImmutableNativeReference::close);
            this.closeList.clear();
            this.worldSections = null;
            this.idMappings = null;
            this.sectionReadOps = null;
            this.sectionWriteOps = null;
            try {
                this.db.closeE();
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            } finally {
                this.db = null;
            }
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }

    private static byte[] intToBytes(int i) {
        return new byte[] { (byte) (i >> 24), (byte) (i >> 16), (byte) (i >> 8), (byte) i };
    }

    private static int bytesToInt(byte[] i) {
        return (Byte.toUnsignedInt(i[0]) << 24) | (Byte.toUnsignedInt(i[1]) << 16) | (Byte.toUnsignedInt(i[2]) << 8)
                | (Byte.toUnsignedInt(i[3]));
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }

    private static long bytesToLong(final byte[] b) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new RocksDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
        }

        public static String getConfigTypeName() {
            return "RocksDB";
        }
    }

}
