/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.catbox.targetpreparer;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;

import java.util.Objects;

/**
 * Sets the device into a low performance state for the test duration
 */
@OptionClass(alias = "low-performance")
public class LowPerformanceTargetPreparer extends BaseTargetPreparer {
    /**
     * An object containing strings with device info data
     */
    private static class OemDeviceInfo {
        private final String mNrCpus;
        private final String mMem;

        public OemDeviceInfo(String nrCpus, String mem) {
            mNrCpus = nrCpus;
            mMem = mem;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof OemDeviceInfo o) {
                return o.mMem.equals(this.mMem) && o.mNrCpus.equals(this.mNrCpus);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mNrCpus, mMem);
        }
    }

    @Option(name = "nr-cpus", description = "Limit number of cores")
    private String mNrCpus = "4";

    @Option(name = "mem", description = "Limit memory in gb")
    private String mMem = "4";

    private OemDeviceInfo mLowPerformanceDeviceInfo;
    private OemDeviceInfo mInitialDeviceInfo;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        mLowPerformanceDeviceInfo = new OemDeviceInfo(mNrCpus, mMem);

        ITestDevice device = testInformation.getDevice();
        device.rebootIntoBootloader();
        try {
            mInitialDeviceInfo = getOemDeviceInfo(device);
            executeFastbootCommand(device,
                    String.format("oem nr-cpus %s", mLowPerformanceDeviceInfo.mNrCpus));
            executeFastbootCommand(device,
                    String.format("oem mem %s", mLowPerformanceDeviceInfo.mMem));
            if (!isDeviceInLowPerformanceState(device)) {
                throw new TargetSetupError("Device is not in a low performance state after setUp.",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.INVOCATION_CANCELLED);
            }
        } finally {
            device.reboot();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        device.rebootIntoBootloader();
        try {
            executeFastbootCommand(device, String.format("oem mem %s", mInitialDeviceInfo.mMem));
            executeFastbootCommand(device,
                    String.format("oem nr-cpus %s", mInitialDeviceInfo.mNrCpus));
            if (isDeviceInLowPerformanceState(device)) {
                throw new TargetSetupError("Failed to reset device to the initial state.");
            }
        } catch (TargetSetupError exception) {
            LogUtil.CLog.e(exception);
            throw new DeviceNotAvailableException("Failed to reset device to the initial state.",
                    exception,
                    device.getSerialNumber());
        } finally {
            device.reboot();
        }
    }

    private CommandResult executeFastbootCommand(ITestDevice device, String command)
            throws DeviceNotAvailableException, TargetSetupError {
        if (!device.isStateBootloaderOrFastbootd()) {
            throw new TargetSetupError(
                    "Device is not in fastboot mode",
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR
            );
        }
        LogUtil.CLog.v(String.format("Executing fastboot command: %s", command));
        final CommandResult result = device.executeFastbootCommand(command.split("\\s+"));
        if (result.getExitCode() != 0) {
            throw new TargetSetupError(
                    String.format(
                            "Command %s failed, stdout = [%s], stderr = [%s].",
                            command, result.getStdout(), result.getStderr()),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        LogUtil.CLog.v(String.format(
                "Command %s returned: stdout = [%s], stderr = [%s].",
                command, result.getStdout(), result.getStderr()));
        return result;
    }

    private OemDeviceInfo getOemDeviceInfo(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        String mem = "";
        String nrCpus = "";

        CommandResult deviceInfoCmdResult = executeFastbootCommand(device, "oem device-info");
        for (String line : deviceInfoCmdResult.getStderr().split("\n")) {
            // (bootloader) Nr cpus: 4
            // (bootloader) Mem Size: 4G
            String[] split = line.split(": ");
            if (split.length == 2) {
                if (split[0].equals("(bootloader) Nr cpus")) {
                    nrCpus = split[1];
                }
                if (split[0].equals("(bootloader) Mem Size")) {
                    mem = split[1].replaceAll("\\D", "");
                }
            }
        }
        if (nrCpus.isBlank() || mem.isBlank()) {
            throw new TargetSetupError(String.format(
                    "Couldn't get current memory or CPU cores values. CPU: %s. Memory: %s.",
                    nrCpus, mem), device.getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        return new OemDeviceInfo(nrCpus, mem);
    }

    private boolean isDeviceInLowPerformanceState(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        return mLowPerformanceDeviceInfo.equals(getOemDeviceInfo(device));
    }
}
