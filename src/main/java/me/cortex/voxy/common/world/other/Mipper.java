package me.cortex.voxy.common.world.other;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        //TODO: do a stable sort on all the entires, w.r.t the opacity and maybe light as a secondary???
        // then select the highest value
        // UPDATE, dumbass, the highest value _is_ the max/min



        int max = -1;

        //TODO: mip with respect to all the variables, what that means is take whatever has the highest count and return that
        //TODO: also average out the light level and set that as the new light level
        //For now just take the most top corner

        //TODO: i think it needs to compute the _max_ light level, since e.g. if a point is bright irl
        // you can see it from really really damn far away.
        // it could be a heavily weighted average with a huge preference to the top most lighting value
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101)<<4)|0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
        }

        if (max != -1) {
            long picked = switch (max&0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max&0b111));
            };
            // Resolved (2026-04-20): use MAX light across all 8 source voxels (sky and
            // block separately) instead of the picked voxel's light. The picked voxel
            // is chosen for opacity, not lighting, so a tree trunk in shadow can be
            // selected and inherit sky=0/block=0, producing pitch-black mip blocks at
            // distance. Per the author's note above ("a point bright irl is visible
            // from far"), max is the right reduction here.
            return withLight(picked, maxLight(I000, I001, I010, I011, I100, I101, I110, I111));
        } else {
            int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                    (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
            int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                    (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
            // Resolved (2026-04-19): blockLight is summed with `& 0xF0` so each term is already
            // at the high-nibble position; after /8 it is still high-nibble-aligned. The previous
            // `<< 4` over-shifted past the byte and withLight() masked the bits away, silently
            // zeroing the block-light component for air mips.
            // The /8 average can leave residue bits in the low nibble (sum not a
            // multiple of 8), which would corrupt skyLight through the OR below.
            blockLight = (blockLight / 8) & 0xF0;
            skyLight = (int) Math.ceil((double) skyLight / 8);

            return withLight(I111, blockLight | skyLight);
        }
    }

    // Returns the per-channel max of block-light (high nibble) and sky-light (low nibble)
    // across the 8 input voxels, packed into a single byte ready for withLight().
    static int maxLight(long v000, long v001, long v010, long v011,
                        long v100, long v101, long v110, long v111) {
        int maxBlock = 0;
        int maxSky = 0;
        int l;
        l = Mapper.getLightId(v000); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v001); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v010); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v011); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v100); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v101); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v110); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        l = Mapper.getLightId(v111); if ((l & 0xF0) > maxBlock) maxBlock = l & 0xF0; if ((l & 0x0F) > maxSky) maxSky = l & 0x0F;
        return maxBlock | maxSky;
    }
}
