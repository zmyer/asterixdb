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
package org.apache.asterix.external.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.common.exceptions.RuntimeDataException;
import org.apache.asterix.common.library.ILibraryManager;
import org.apache.hyracks.algebricks.common.utils.Pair;

public class ExternalLibraryManager implements ILibraryManager {

    private final Map<String, ClassLoader> libraryClassLoaders = new HashMap<>();

    @Override
    public void registerLibraryClassLoader(String dataverseName, String libraryName, ClassLoader classLoader)
            throws RuntimeDataException {
        String key = getKey(dataverseName, libraryName);
        synchronized (libraryClassLoaders) {
            if (libraryClassLoaders.get(key) != null) {
                throw new RuntimeDataException(ErrorCode.LIBRARY_EXTERNAL_LIBRARY_CLASS_REGISTERED);
            }
            libraryClassLoaders.put(key, classLoader);
        }
    }

    @Override
    public List<Pair<String, String>> getAllLibraries() {
        ArrayList<Pair<String, String>> libs = new ArrayList<>();
        synchronized (libraryClassLoaders) {
            libraryClassLoaders.forEach((key, value) -> libs.add(getDataverseAndLibararyName(key)));
        }
        return libs;
    }

    @Override
    public void deregisterLibraryClassLoader(String dataverseName, String libraryName) {
        String key = getKey(dataverseName, libraryName);
        synchronized (libraryClassLoaders) {
            if (libraryClassLoaders.get(key) != null) {
                libraryClassLoaders.remove(key);
            }
        }
    }

    @Override
    public ClassLoader getLibraryClassLoader(String dataverseName, String libraryName) {
        String key = getKey(dataverseName, libraryName);
        return libraryClassLoaders.get(key);
    }

    private static String getKey(String dataverseName, String libraryName) {
        return dataverseName + "." + libraryName;
    }

    private static Pair<String, String> getDataverseAndLibararyName(String key) {
        int index = key.indexOf('.');
        String dataverse = key.substring(0, index);
        String library = key.substring(index + 1);
        return new Pair<>(dataverse, library);
    }

}
