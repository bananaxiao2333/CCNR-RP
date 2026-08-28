/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ccnrcom.rp.data.orm.Column;
import com.ccnrcom.rp.data.orm.Id;
import com.ccnrcom.rp.data.orm.JsonColumn;
import com.ccnrcom.rp.data.orm.SqlMapper;
import com.ccnrcom.rp.data.orm.Table;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlMapperTest {

    @Table("docs")
    record Doc(@Id @Column("k") String key, @Column("v") long val, @JsonColumn @Column("payload") JsonObject payload) {}

    @Test
    void roundtripInMemorySqlite() throws Exception {
        SqlMapper<Doc> mapper = new SqlMapper<>(Doc.class);
        assertEquals("docs", mapper.table());
        assertEquals(List.of("k"), mapper.idColumns());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (PreparedStatement st =
                    conn.prepareStatement("CREATE TABLE docs (k TEXT, v INTEGER, payload TEXT, PRIMARY KEY (k))")) {
                st.execute();
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("name", "陆佐");
            payload.addProperty("xp", 42);
            Doc doc = new Doc("1a2b", 7, payload);
            try (PreparedStatement st = conn.prepareStatement("INSERT INTO docs (k, v, payload) VALUES (?, ?, ?)")) {
                mapper.bind(st, doc);
                st.executeUpdate();
            }
            Doc read;
            try (PreparedStatement st = conn.prepareStatement("SELECT k, v, payload FROM docs")) {
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    read = mapper.map(rs);
                }
            }
            assertEquals("1a2b", read.key());
            assertEquals(7, read.val());
            assertEquals(42, read.payload().get("xp").getAsInt());
            assertEquals("陆佐", read.payload().get("name").getAsString());
        }
    }
}
