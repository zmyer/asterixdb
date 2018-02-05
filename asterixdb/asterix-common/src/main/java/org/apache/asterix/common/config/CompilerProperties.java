/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.common.config;

import static org.apache.hyracks.control.common.config.OptionTypes.INTEGER;
import static org.apache.hyracks.control.common.config.OptionTypes.INTEGER_BYTE_UNIT;
import static org.apache.hyracks.control.common.config.OptionTypes.LONG_BYTE_UNIT;
import static org.apache.hyracks.control.common.config.OptionTypes.STRING;
import static org.apache.hyracks.util.StorageUtil.StorageUnit.KILOBYTE;
import static org.apache.hyracks.util.StorageUtil.StorageUnit.MEGABYTE;

import org.apache.hyracks.api.config.IOption;
import org.apache.hyracks.api.config.IOptionType;
import org.apache.hyracks.api.config.Section;
import org.apache.hyracks.util.StorageUtil;

public class CompilerProperties extends AbstractProperties {

    public enum Option implements IOption {
        COMPILER_SORTMEMORY(
                LONG_BYTE_UNIT,
                StorageUtil.getLongSizeInBytes(32L, MEGABYTE),
                "The memory budget (in bytes) for a sort operator instance in a partition"),
        COMPILER_JOINMEMORY(
                LONG_BYTE_UNIT,
                StorageUtil.getLongSizeInBytes(32L, MEGABYTE),
                "The memory budget (in bytes) for a join operator instance in a partition"),
        COMPILER_GROUPMEMORY(
                LONG_BYTE_UNIT,
                StorageUtil.getLongSizeInBytes(32L, MEGABYTE),
                "The memory budget (in bytes) for a group by operator instance in a partition"),
        COMPILER_FRAMESIZE(
                INTEGER_BYTE_UNIT,
                StorageUtil.getIntSizeInBytes(32, KILOBYTE),
                "The page size (in bytes) for computation"),
        COMPILER_PARALLELISM(
                INTEGER,
                COMPILER_PARALLELISM_AS_STORAGE,
                "The degree of parallelism for query "
                        + "execution. Zero means to use the storage parallelism as the query execution parallelism, while "
                        + "other integer values dictate the number of query execution parallel partitions. The system will "
                        + "fall back to use the number of all available CPU cores in the cluster as the degree of parallelism "
                        + "if the number set by a user is too large or too small"),
        COMPILER_PREGELIX_HOME(STRING, "~/pregelix", "Pregelix installation root directory");

        private final IOptionType type;
        private final Object defaultValue;
        private final String description;

        Option(IOptionType type, Object defaultValue, String description) {
            this.type = type;
            this.defaultValue = defaultValue;
            this.description = description;
        }

        @Override
        public Section section() {
            return Section.COMMON;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public IOptionType type() {
            return type;
        }

        @Override
        public Object defaultValue() {
            return defaultValue;
        }

        @Override
        public boolean hidden() {
            return this == COMPILER_PREGELIX_HOME;
        }
    }

    public static final String COMPILER_SORTMEMORY_KEY = Option.COMPILER_SORTMEMORY.ini();

    public static final String COMPILER_GROUPMEMORY_KEY = Option.COMPILER_GROUPMEMORY.ini();

    public static final String COMPILER_JOINMEMORY_KEY = Option.COMPILER_JOINMEMORY.ini();

    public static final String COMPILER_PARALLELISM_KEY = Option.COMPILER_PARALLELISM.ini();

    public static final int COMPILER_PARALLELISM_AS_STORAGE = 0;

    public CompilerProperties(PropertiesAccessor accessor) {
        super(accessor);
    }

    public long getSortMemorySize() {
        return accessor.getLong(Option.COMPILER_SORTMEMORY);
    }

    public long getJoinMemorySize() {
        return accessor.getLong(Option.COMPILER_JOINMEMORY);
    }

    public long getGroupMemorySize() {
        return accessor.getLong(Option.COMPILER_GROUPMEMORY);
    }

    public int getFrameSize() {
        return accessor.getInt(Option.COMPILER_FRAMESIZE);
    }

    public int getParallelism() {
        return accessor.getInt(Option.COMPILER_PARALLELISM);
    }

    public String getPregelixHome() {
        return accessor.getString(Option.COMPILER_PREGELIX_HOME);
    }
}
