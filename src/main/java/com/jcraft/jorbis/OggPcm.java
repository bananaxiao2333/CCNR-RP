/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.jcraft.jorbis;

import java.io.ByteArrayOutputStream;

/** jorbis（com.googlecode.soundlibs 0.0.17.4 fork）包内辅助：VorbisFile 的 PCM 读取方法为包私有，此处暴露公开解码。 */
public final class OggPcm {
    private OggPcm() {}

    /**
     * 整段解码 OGG → 16bit 小端有符号 PCM 字节（全部解码到内存）。
     *
     * @return Object[]{byte[] pcm, Integer channels, Integer rate}
     */
    public static Object[] decodeAll(VorbisFile vf) throws Exception {
        Info vi = vf.getInfo(-1);
        int channels = vi.channels;
        int rate = vi.rate;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[8192];
        int got;
        while ((got = vf.read(buf, buf.length, 0, 2, 1, null)) > 0) {
            out.write(buf, 0, got);
        }
        return new Object[] {out.toByteArray(), channels, rate};
    }
}
