/*
 * FileOptions.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2024 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.relational.yamltests.block;

import com.apple.foundationdb.relational.yamltests.CustomYamlConstructor;
import com.apple.foundationdb.relational.yamltests.Matchers;
import com.apple.foundationdb.relational.yamltests.YamlExecutionContext;
import com.apple.foundationdb.relational.yamltests.server.SemanticVersion;
import com.apple.foundationdb.relational.yamltests.server.SupportedVersionCheck;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assumptions;

import java.util.Map;

/**
 * Block that configures aspects of the test that do not require a connection yet.
 * <p>
 *     This supports the following sub-commands:
 *     <ul>
 *         <li>{@code supported_version}: if this is set, it will disable the test when running against a server
 *         older than a specific version. The special yaml tag {@code !current_version} can be used to indicate that
 *         it should work against the current code, but is not expected to work with any older versions.
 *         In the future, the release script will update these automatically to the version being released.
 *         <p>
 *             <b>Example:</b>
 *             <pre>{@code
 *                 ---
 *                 options:
 *                     supported_version: !current_version
 *             }</pre>
 *     </ul>
 */
public class FileOptions {
    public static final String OPTIONS = "options";
    public static final String SUPPORTED_VERSION_OPTION = "supported_version";
    private static final Logger logger = LogManager.getLogger(FileOptions.class);

    public static Block parse(int lineNumber, Object document, YamlExecutionContext executionContext) {
        final Map<?, ?> options = CustomYamlConstructor.LinedObject.unlineKeys(Matchers.map(document, OPTIONS));
        final SupportedVersionCheck check = SupportedVersionCheck.parseOptions(options, executionContext);
        if (!check.isSupported()) {
            // IntelliJ, at least, doesn't display the reason, so log it
            if (logger.isInfoEnabled()) {
                logger.info(check.getMessage());
            }
            Assumptions.assumeTrue(check.isSupported(), check.getMessage());
        }
        return new NoOpBlock(lineNumber);
    }

    public static SemanticVersion parseVersion(Object rawVersion) {
        if (rawVersion instanceof CurrentVersion) {
            return SemanticVersion.current();
        } else if (rawVersion instanceof String) {
            return SemanticVersion.parse((String) rawVersion);
        } else {
            throw new IllegalArgumentException("Unable to determine semantic version from object: " + rawVersion);
        }
    }

    public static final class CurrentVersion {
        public static final CurrentVersion INSTANCE = new CurrentVersion();
        public static final String TEXT = SemanticVersion.SemanticVersionType.CURRENT.getText();

        private CurrentVersion() {
        }

        @Override
        public String toString() {
            return TEXT;
        }
    }
}
