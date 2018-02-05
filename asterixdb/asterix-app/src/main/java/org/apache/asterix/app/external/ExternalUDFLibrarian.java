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
package org.apache.asterix.app.external;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.rmi.RemoteException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.library.ILibraryManager;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FileUtils;
import org.apache.hyracks.algebricks.common.utils.Pair;

@SuppressWarnings("squid:S134")
public class ExternalUDFLibrarian implements IExternalUDFLibrarian {

    // The following list includes a library manager for the CC
    // and library managers for NCs (one-per-NC).
    private final List<ILibraryManager> libraryManagers;

    public ExternalUDFLibrarian(List<ILibraryManager> libraryManagers) {
        this.libraryManagers = libraryManagers;
    }

    public static void removeLibraryDir() throws IOException {
        File installLibDir = ExternalLibraryUtils.getLibraryInstallDir();
        FileUtils.deleteQuietly(installLibDir);
    }

    public static void unzip(String sourceFile, String outputDir) throws IOException {
        if (System.getProperty("os.name").toLowerCase().startsWith("win")) {
            try (ZipFile zipFile = new ZipFile(sourceFile)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File entryDestination = new File(outputDir, entry.getName());
                    if (!entry.isDirectory()) {
                        entryDestination.getParentFile().mkdirs();
                        try (InputStream in = zipFile.getInputStream(entry);
                                OutputStream out = new FileOutputStream(entryDestination)) {
                            IOUtils.copy(in, out);
                        }
                    }
                }
            }
        } else {
            Process process = new ProcessBuilder("unzip", "-d", outputDir, sourceFile).start();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    @Override
    public void install(String dvName, String libName, String libPath) throws Exception {
        // get the directory of the to be installed libraries
        File installLibDir = ExternalLibraryUtils.getLibraryInstallDir();
        // directory exists?
        if (!installLibDir.exists()) {
            installLibDir.mkdir();
        }
        // copy the library file into the directory
        File destinationDir =
                new File(installLibDir.getAbsolutePath() + File.separator + dvName + File.separator + libName);
        FileUtils.deleteQuietly(destinationDir);
        destinationDir.mkdirs();
        try {
            unzip(libPath, destinationDir.getAbsolutePath());
        } catch (Exception e) {

            throw new Exception("Couldn't unzip the file: " + libPath, e);
        }

        for (ILibraryManager libraryManager : libraryManagers) {
            ExternalLibraryUtils.registerLibrary(libraryManager, dvName, libName);
        }
        // get library file
        // install if needed (add functions, adapters, datasources, parsers to the metadata)
        // <Not required for use>
        ExternalLibraryUtils.installLibraryIfNeeded(dvName, destinationDir, new HashMap<>());
    }

    @Override
    public void uninstall(String dvName, String libName) throws RemoteException, AsterixException, ACIDException {
        ExternalLibraryUtils.uninstallLibrary(dvName, libName);
        for (ILibraryManager libraryManager : libraryManagers) {
            libraryManager.deregisterLibraryClassLoader(dvName, libName);
        }
    }

    public void cleanup() throws AsterixException, RemoteException, ACIDException {
        for (ILibraryManager libraryManager : libraryManagers) {
            List<Pair<String, String>> libs = libraryManager.getAllLibraries();
            for (Pair<String, String> dvAndLib : libs) {
                ExternalLibraryUtils.uninstallLibrary(dvAndLib.first, dvAndLib.second);
                libraryManager.deregisterLibraryClassLoader(dvAndLib.first, dvAndLib.second);
            }
        }
        // get the directory of the to be installed libraries
        File installLibDir = ExternalLibraryUtils.getLibraryInstallDir();
        FileUtils.deleteQuietly(installLibDir);
    }
}
