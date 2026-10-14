package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Efficient serialization and deserialization of WorldSection data for network
 * transfer.
 * <p>
 * Format:
 * 
 * <pre>
 * [Header: 21 bytes]
 *   - version: 1 byte
 *   - level: 1 byte
 *   - x, y, z: 4 bytes each (12 total)
 *   - nonEmptyChildren: 1 byte
 *   - dataLength: 4 bytes
 *   - compressionType: 1 byte (0=none, 1=gzip)
 *   - hasData: 1 byte
 * 
 * [Data: variable]
 *   - Compressed voxel data (if hasData)
 * </pre>
 */
public class SectionSerializer {

    public static final byte VERSION = 6;

    public static final byte COMPRESSION_NONE = 0;
    public static final byte COMPRESSION_GZIP = 1;
    public static final byte COMPRESSION_LZ4 = 2;

    // LZ4 is roughly 10x faster than GZIP on this payload (32^3 longs ≈ 256 KB
    // per section), at the cost of ~1.3x larger output. The streaming layer
    // serializes thousands of sections per drain so the throughput improvement
    // pays for itself many times over.
    private static final net.jpountz.lz4.LZ4Compressor LZ4_COMPRESSOR =
            LZ4Factory.fastestInstance().fastCompressor();
    private static final LZ4FastDecompressor LZ4_DECOMPRESSOR =
            LZ4Factory.fastestInstance().fastDecompressor();

    /**
     * Serialize a WorldSection to bytes for network transfer.
     */
    public static byte[] serialize(WorldSection section) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos)) {

            // Header
            out.writeByte(VERSION);
            out.writeByte(section.lvl);
            out.writeInt(section.x);
            out.writeInt(section.y);
            out.writeInt(section.z);
            out.writeByte(section.getNonEmptyChildren());

            // Check if section has any data
            boolean hasData = section.getNonEmptyBlockCount() > 0 || section.getNonEmptyChildren() != 0;

            if (hasData) {
                // Serialize voxel data
                byte[] voxelData = serializeVoxelData(section);

                // LZ4 (fast). Prepend uncompressed length so decompressor knows the
                // expected output size — LZ4FastDecompressor needs it.
                int rawLen = voxelData.length;
                byte[] compressed = new byte[LZ4_COMPRESSOR.maxCompressedLength(rawLen)];
                int compLen = LZ4_COMPRESSOR.compress(voxelData, 0, rawLen, compressed, 0, compressed.length);

                boolean useCompression = compLen + 4 < rawLen;
                int payloadLen = useCompression ? compLen + 4 : rawLen;
                out.writeInt(payloadLen);
                out.writeByte(useCompression ? COMPRESSION_LZ4 : COMPRESSION_NONE);
                out.writeByte(1); // hasData = true
                if (useCompression) {
                    out.writeInt(rawLen);
                    out.write(compressed, 0, compLen);
                } else {
                    out.write(voxelData);
                }
            } else {
                out.writeInt(0);
                out.writeByte(COMPRESSION_NONE);
                out.writeByte(0); // hasData = false
            }

            return baos.toByteArray();

        } catch (IOException e) {
            Logger.error("Failed to serialize section: " + e.getMessage());
            Logger.error(e);
            return new byte[0];
        }
    }

    /**
     * Deserialize bytes into WorldSection data.
     * Returns a SectionData holder with the deserialized information.
     */
    public static SectionData deserialize(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {

            byte version = in.readByte();
            if (version != VERSION) {
                Logger.warn("Unknown section serialization version: " + version);
                // Try to handle older versions gracefully
            }

            int level = in.readByte() & 0xFF;
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            byte nonEmptyChildren = in.readByte();

            int dataLength = in.readInt();
            byte compression = in.readByte();
            boolean hasData = in.readByte() != 0;

            long[] voxelData = null;
            if (hasData && dataLength > 0) {
                byte[] rawData;
                if (compression == COMPRESSION_LZ4) {
                    int decompLen = in.readInt();
                    if (decompLen < 0 || decompLen > 32 * 32 * 32 * 8 + 16) {
                        Logger.warn("Invalid LZ4 decompressed size: " + decompLen);
                        return null;
                    }
                    byte[] comp = new byte[dataLength - 4];
                    in.readFully(comp);
                    rawData = new byte[decompLen];
                    LZ4_DECOMPRESSOR.decompress(comp, 0, rawData, 0, decompLen);
                } else if (compression == COMPRESSION_GZIP) {
                    byte[] comp = new byte[dataLength];
                    in.readFully(comp);
                    rawData = decompressGzip(comp);
                } else {
                    rawData = new byte[dataLength];
                    in.readFully(rawData);
                }

                voxelData = deserializeVoxelData(rawData);
            }

            return new SectionData(level, x, y, z, nonEmptyChildren, voxelData);

        } catch (IOException e) {
            Logger.error("Failed to deserialize section: " + e.getMessage());
            Logger.error(e);
            return null;
        }
    }

    /**
     * Serialize just the voxel data array.
     */
    private static byte[] serializeVoxelData(WorldSection section) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // Get the data array
        long[] data = section._unsafeGetRawDataArray();
        if (data == null) {
            out.writeInt(0);
            return baos.toByteArray();
        }

        // Write voxel count
        int voxelCount = data.length;
        out.writeInt(voxelCount);

        // Write each voxel as a long
        for (int i = 0; i < voxelCount; i++) {
            out.writeLong(data[i]);
        }

        return baos.toByteArray();
    }

    /**
     * Deserialize voxel data array.
     */
    private static long[] deserializeVoxelData(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

        int voxelCount = in.readInt();
        if (voxelCount <= 0) {
            return null;
        }

        long[] voxels = new long[voxelCount];
        for (int i = 0; i < voxelCount; i++) {
            voxels[i] = in.readLong();
        }

        return voxels;
    }

    /**
     * Legacy GZIP decompression — kept so clients running this jar can still
     * read sections written by older versions during a rolling upgrade.
     */
    private static byte[] decompressGzip(byte[] data) throws IOException {
        try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data));
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Holder for deserialized section data.
     */
    public static class SectionData {
        public final int level;
        public final int x;
        public final int y;
        public final int z;
        public final byte nonEmptyChildren;
        public final long[] voxelData;

        public SectionData(int level, int x, int y, int z, byte nonEmptyChildren, long[] voxelData) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.nonEmptyChildren = nonEmptyChildren;
            this.voxelData = voxelData;
        }

        /**
         * Get the section key for WorldEngine lookup.
         */
        public long getKey() {
            return WorldEngine.getWorldSectionId(level, x, y, z);
        }

        /**
         * Check if this section has voxel data.
         */
        public boolean hasData() {
            return voxelData != null && voxelData.length > 0;
        }
    }
}
