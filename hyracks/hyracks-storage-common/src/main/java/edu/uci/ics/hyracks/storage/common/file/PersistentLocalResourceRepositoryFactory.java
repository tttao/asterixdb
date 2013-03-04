/*
 * Copyright 2009-2012 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.storage.common.file;

import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.io.IIOManager;
import edu.uci.ics.hyracks.api.io.IODeviceHandle;

public class PersistentLocalResourceRepositoryFactory implements ILocalResourceRepositoryFactory {
    private static final String rootDir = "";
    private IIOManager ioManager;

    public PersistentLocalResourceRepositoryFactory(IIOManager ioManager) {
        this.ioManager = ioManager;
    }

    @Override
    public ILocalResourceRepository createRepository() throws HyracksDataException {
        List<String> mountPoints = new ArrayList<String>();
        List<IODeviceHandle> devices = ioManager.getIODevices();

        for (IODeviceHandle dev : devices) {
            mountPoints.add(dev.getPath().getPath());
        }
        return new PersistentLocalResourceRepository(mountPoints, rootDir);
    }
}
