/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** MusicStore 名称/格式校验单测（不触磁盘）。 */
class MusicStoreTest {

    @Test
    void acceptsValidWavName() {
        assertNull(MusicStore.validateName("de_mulan.wav"));
        assertNull(MusicStore.validateName("A-B_1.wav"));
    }

    @Test
    void rejectsBadNames() {
        assertNotNull(MusicStore.validateName("de_mulan.mp3"));
        assertNotNull(MusicStore.validateName("de_mulan"));
        assertNotNull(MusicStore.validateName("../de_mulan.wav"));
        assertNotNull(MusicStore.validateName("a/b.wav"));
        assertNotNull(MusicStore.validateName(""));
        assertNotNull(MusicStore.validateName("1234567890123456789012345678901234567890123456789.wav"));
        assertNotNull(MusicStore.validateName(null));
    }

    @Test
    void acceptsRiffWav() {
        byte[] b = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertNull(MusicStore.validate(b));
    }

    @Test
    void rejectsNonWav() {
        byte[] b = "PK\u0003\u0004RIFFWAVE".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertNotNull(MusicStore.validate(b));
        assertNotNull(MusicStore.validate(new byte[0]));
        assertNotNull(MusicStore.validate(null));
    }

    @Test
    void rejectsOversize() {
        byte[] big = new byte[MusicStore.MAX_BYTES + 1];
        big[0] = 'R';
        big[1] = 'I';
        big[2] = 'F';
        big[3] = 'F';
        big[8] = 'W';
        big[9] = 'A';
        big[10] = 'V';
        big[11] = 'E';
        assertEquals("大小超限（≤ 20MB）", MusicStore.validate(big));
    }
}
