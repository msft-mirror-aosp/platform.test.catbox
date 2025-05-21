/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.CollectingOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.TestAppInstallSetup;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.OutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@OptionClass(alias = "youtube-md-passenger-load")
public class YoutubeMdPassengerLoadPreparer extends BaseTargetPreparer {

    @Option(name = "skip-display-ids", description = "Display IDs to skip passenger load for")
    private List<Integer> mSkipDisplayIds = new ArrayList<>();

    @Option(
            name = "override-user-ids",
            description = "Manually override the display IDS with pre-created users")
    private String mOverrideUserIds = "";

    @Option(
            name = "skip-passenger-loading",
            description = "Only create additional passenger users, skip loading them")
    boolean skipPassengerLoading = false;

    @Option(
            name = "skip-driver-loading",
            description = "Only load the passenger users, skip loading the driver")
    boolean skipDriverLoading = true;

    @Option(name = "post-test-cleanup", description = "Clean up users and uninstall test apks")
    boolean postTestCleanup = false;

    @Option(
            name = "skip-device-setup",
            description = "Skips exiting set-up wizard and accepting gTOS")
    boolean skipDeviceSetup = false;

    @Option(name = "urls", description = "Youtube video URLs separated by commas", mandatory = true)
    private String mUrls;

    @Option(name = "package", description = "Youtube package")
    private String mPackage = "com.google.android.apps.automotive.youtube";

    @Option(name = "install-apk", description = "Re-install a custom Youtube APK if necessary")
    boolean mInstallYTApk = false;

    @Option(name = "max-users", description = "Maximum number of users to support")
    int maxUsers = 10;

    @Option(
            name = "test-app-file-name",
            description = "full qualified path to the custom Youtube APK")
    private List<String> mTestFiles = new ArrayList<>();

    private HashMap<Integer, Integer> mDisplayToCreatedUsers = new HashMap<>();
    private final ArrayList<TestAppInstallSetup> mInstallPreparers =
            new ArrayList<TestAppInstallSetup>();
    private int mYtLaunches = 0;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        try {
            ITestDevice device = testInfo.getDevice();
            increaseSupportedUsers(device);
            if (mOverrideUserIds.length() == 0) {
                Set<Integer> displayIds = device.listDisplayIdsForStartingVisibleBackgroundUsers();
                for (Integer displayId : displayIds) {
                    if (mSkipDisplayIds.contains(displayId)) {
                        LogUtil.CLog.d("Skipping user creation for display %d", displayId);
                        continue;
                    }
                    int userId = createAndStartUser(device, displayId);
                    LogUtil.CLog.d(
                            "Created and started new passenger user: %s on Display: %s",
                            userId, displayId);
                    mDisplayToCreatedUsers.put(displayId, userId);
                }
            } else {
                int displayId = 1;
                for (String userId : mOverrideUserIds.split(",")) {
                    LogUtil.CLog.d(
                            "Created and started new passenger user: %s on Display: %s",
                            userId, displayId);
                    mDisplayToCreatedUsers.put(displayId, Integer.parseInt(userId));
                    displayId++;
                }
            }

            // Assume current user is on the main display(this is needed for driver loading)
            if (!skipDriverLoading) {
                int currentUser = device.getCurrentUser();
                LogUtil.CLog.d("Mapping current user %d to display 0", currentUser);
                mDisplayToCreatedUsers.put(0, currentUser);
            }

            if (!skipDeviceSetup) {
                deviceSetup(device);
            }

            if (!skipPassengerLoading && mInstallYTApk) {
                installApk(testInfo);
            }

            for (Integer displayId : mDisplayToCreatedUsers.keySet()) {
                int userId = mDisplayToCreatedUsers.get(displayId);
                mYtLaunches = 0; // Reset retry counter for each new user's attempt
                simulatePassengerLoad(device, userId);
            }
        } catch (TargetSetupError e) {
            LogUtil.CLog.e("Set-up failed: " + e.getMessage());
            try {
                tearDown(testInfo, e);
            } catch (DeviceNotAvailableException tearDownException) {
                LogUtil.CLog.e("Teardown failed: " + tearDownException.getMessage());
                e.addSuppressed(tearDownException);
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (!skipPassengerLoading) {
            stopTestApps(device);
        }

        stopUsers(device);

        if (postTestCleanup) {
            // Remove all the passenger users
            for (int userId : mDisplayToCreatedUsers.values()) {
                LogUtil.CLog.d("Removing user: %s", userId);
                device.removeUser(userId);
            }
        }
        device.reboot();
    }

    private void deviceSetup(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        skipGtos(device);
        skipSuw(device);
    }

    private void stopTestApps(ITestDevice device) throws DeviceNotAvailableException {
        LogUtil.CLog.d("Stopping the Youtube application for all the passengers");
        for (int userID : mDisplayToCreatedUsers.values()) {
            String stopYoutube = String.format("am force-stop --user %d %s", userID, mPackage);
            CommandResult stopYoutubeResult = device.executeShellV2Command(stopYoutube);
            if (stopYoutubeResult.getExitCode() != 0) {
                LogUtil.CLog.d("Failed to kill the Youtube application for user: %d", userID);
            }
        }
    }

    private void increaseSupportedUsers(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        LogUtil.CLog.d("Temporarily increasing maximum supported users to " + maxUsers);
        String setMaxUsers = "setprop fw.max_users " + maxUsers;
        CommandResult setMaxUsersResult = device.executeShellV2Command(setMaxUsers);
        if (!CommandStatus.SUCCESS.equals(setMaxUsersResult.getStatus())) {
            throw new TargetSetupError(
                    "Failed to increase the number of supported users",
                    device.getDeviceDescriptor());
        }
        LogUtil.CLog.d("Successfully increased the maximum supported users");
    }

    private int createAndStartUser(ITestDevice device, int displayId)
            throws TargetSetupError, DeviceNotAvailableException {
        int userId = device.createUser(String.format("user-display-%d", displayId));
        LogUtil.CLog.d(String.format("Created user with id %d for display %d", userId, displayId));
        if (!device.startVisibleBackgroundUser(userId, displayId, true)) {
            throw new TargetSetupError(
                    String.format("Device failed to switch to user %d", userId),
                    device.getDeviceDescriptor());
        }
        LogUtil.CLog.d(
                String.format("Started background user %d for display %d", userId, displayId));
        return userId;
    }

    private void stopUsers(ITestDevice device) throws DeviceNotAvailableException {
        LogUtil.CLog.d("Stopping all passenger users");
        for (int userID : mDisplayToCreatedUsers.values()) {
            String startUserCommand = String.format("am stop-user %d", userID);
            CommandResult startUserResult = device.executeShellV2Command(startUserCommand);
            if (startUserResult.getExitCode() != 0) {
                LogUtil.CLog.d("Failed to stop the user: %d", userID);
            }
        }
        LogUtil.CLog.d("Successfully stopped all passenger users");
    }

    private void installApk(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        for (int userId : mDisplayToCreatedUsers.values()) {
            TestAppInstallSetup installPreparer = new TestAppInstallSetup();
            LogUtil.CLog.d(
                    String.format(
                            "Installing the following test APKs in user %d: \n%s",
                            userId, mTestFiles));
            installPreparer.setUserId(userId);
            installPreparer.setShouldGrantPermission(true);
            for (String file : mTestFiles) {
                installPreparer.addTestFileName(file);
            }
            installPreparer.addInstallArg("-r");
            installPreparer.addInstallArg("-d");
            installPreparer.setUp(testInfo);
            mInstallPreparers.add(installPreparer);
        }
    }

    private void simulatePassengerLoad(ITestDevice device, int userId)
            throws TargetSetupError, DeviceNotAvailableException {
        String youtubeUrl =
                mUrls.split(",").length == 0
                        ? null
                        : new Random()
                                .ints(0, mUrls.split(",").length)
                                .limit(1)
                                .mapToObj(index -> mUrls.split(",")[index])
                                .findFirst()
                                .orElse(null);
        LogUtil.CLog.d(
                String.format(
                        "Launching the Youtube App for User: %d with url: %s", userId, youtubeUrl));
        launchYoutube(device, userId, youtubeUrl);
    }

    private void launchYoutube(ITestDevice device, int userId, String url)
            throws TargetSetupError, DeviceNotAvailableException {
        String launchYoutubeWithUrlCommand =
                String.format(
                        "am start --user %d -a android.intent.action.VIEW -e FullScreen true  -d "
                                + "\"%s\" %s",
                        userId, url, mPackage);
        LogUtil.CLog.d("Youtube launch command: %s", launchYoutubeWithUrlCommand);
        CommandResult result = device.executeShellV2Command(launchYoutubeWithUrlCommand);
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            throw new TargetSetupError(
                    String.format("Failed to launch the Youtube app for the user %d", userId),
                    device.getDeviceDescriptor());
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new TargetSetupError(
                    String.format("Thread interrupted while launching Youtube for user %d", userId),
                    e,
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
        boolean isYoutubeLaunched = isYoutubeLaunchedForUser(device, userId);
        if (!isYoutubeLaunched) {
            retryYoutubeLaunch(device, userId);
        } else {
            LogUtil.CLog.d("Successfully launched the Youtube video for user: %d", userId);
        }
    }

    private void retryYoutubeLaunch(ITestDevice device, int userId)
            throws TargetSetupError, DeviceNotAvailableException {
        if (mYtLaunches < 2) {
            LogUtil.CLog.d(
                    "Custom Youtube app launch check failed for the user %d, retrying...", userId);
            mYtLaunches++;
            try {
                simulatePassengerLoad(device, userId);
            } catch (TargetSetupError | DeviceNotAvailableException e) {
                throw new TargetSetupError(
                        String.format(
                                "Custom Youtube app launch check failed for the user %d after"
                                        + " retry",
                                userId),
                        e,
                        device.getDeviceDescriptor());
            }
        } else {
            throw new TargetSetupError(
                    String.format("Custom Youtube app launch check failed for the user %d", userId),
                    device.getDeviceDescriptor());
        }
    }

    private boolean isYoutubeLaunchedForUser(ITestDevice device, int userId)
            throws TargetSetupError, DeviceNotAvailableException {
        LogUtil.CLog.d(
                String.format("Checking if the Youtube App is launched for User: %d", userId));
        String checkYoutubeLaunchedCommand =
                String.format(
                        "ps -efw | grep -i %s | awk -F'_' '{print $1}' | grep u%d",
                        mPackage, userId);
        LogUtil.CLog.d("Running the command: %s", checkYoutubeLaunchedCommand);
        CommandResult result = device.executeShellV2Command(checkYoutubeLaunchedCommand);
        LogUtil.CLog.d("Full check command output is %s", result.getStdout());
        String output =
                result.getStdout().split("\n").length > 0
                        ? result.getStdout().split("\n")[0].replaceAll("[^0-9]", "")
                        : ""; // 11
        if (result.getExitCode() != 0
                || output.length() == 0
                || userId != Integer.parseInt(output)) {
            return false;
        } else {
            mYtLaunches = 0;
            return true;
        }
    }

    // Skips the Set-up wizard for all the passenger users.
    private void skipSuw(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
        LogUtil.CLog.d("Skipping set-up wizard for all passenger users");
        for (int displayID : mDisplayToCreatedUsers.keySet()) {
            String suwSkipCommand =
                    String.format(
                            "am start --user %d --display %d -n"
                                    + " com.google.android.car.setupwizard/.ExitActivity",
                            mDisplayToCreatedUsers.get(displayID), displayID);
            CommandResult suwSkipCommandResult = device.executeShellV2Command(suwSkipCommand);
            if (suwSkipCommandResult.getExitCode() != 0) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to skip the set-up wizard for user: %d and display: %d",
                                mDisplayToCreatedUsers.get(displayID), displayID),
                        device.getDeviceDescriptor());
            }
        }
        LogUtil.CLog.d("Successfully skipped set-up wizard across all passenger users");
    }

    // Skips the Google Terms and Conditions and Set-up wizard for all the users.
    private void skipGtos(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
        LogUtil.CLog.d("Skipping gTOS on behalf of all users");
        if (!device.isAdbRoot()) {
            device.enableAdbRoot();
        }
        for (int userID : mDisplayToCreatedUsers.values()) {
            String gTOSPmCommand =
                    String.format(
                            "am broadcast --user %d -a"
                                    + " com.google.android.setupservices.GOOGLE_SERVICES_ACCEPTED"
                                    + " com.google.android.gms ",
                            userID);
            CommandResult gTOSPmResult = device.executeShellV2Command(gTOSPmCommand);
            if (gTOSPmResult.getExitCode() != 0) {
                throw new TargetSetupError(
                        String.format("Failed to skip gTOS for user: %d", userID),
                        device.getDeviceDescriptor());
            }

            String gTOSKeyUserCommand =
                    String.format(
                            "settings put secure --user %d"
                                    + " android.car.ENABLE_INITIAL_NOTICE_SCREEN_TO_USER 0 ",
                            userID);
            CommandResult gTOSKeyUserResult = device.executeShellV2Command(gTOSKeyUserCommand);
            if (gTOSKeyUserResult.getExitCode() != 0) {
                throw new TargetSetupError(
                        String.format("Failed to accept gTOS for user: %d", userID),
                        device.getDeviceDescriptor());
            }
        }
        LogUtil.CLog.d("Successfully skipped gTOS for all passenger users");
    }
}
