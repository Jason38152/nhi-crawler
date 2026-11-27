package com.jason.nhi_crawler.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 從 src/test/resources/fixtures 讀取存好的網頁檔案。
 */
final class FixtureLoader {

    private FixtureLoader() {
    }

    static String load(String name) {
        try (InputStream in = FixtureLoader.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到測試檔案: fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
