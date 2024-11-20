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
import java.io.OutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@OptionClass(alias = "chrome-md-passenger-load")
public class ChromeMdPassengerLoadPreparer extends BaseTargetPreparer {

  @Option(name = "skip-display-id", description = "Display id to skip passenger load for")
  private List<Integer> mSkipDisplayIds = new ArrayList<>();

  @Option(
      name = "skip-passenger-loading",
      description = "Only create additional passenger users, skip loading them")
  boolean skipLoading = false;

  @Option(name = "post-test-cleanup", description = "Clean up users and uninstall test apks")
  boolean postTestCleanup = true;

  @Option(name = "url", description = "Youtube video URL", mandatory = true)
  private String mUrl;

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

  Map<Integer, Integer> mDisplayToCreatedUsers = new HashMap<>();
  private final ArrayList<TestAppInstallSetup> mInstallPreparers =
      new ArrayList<TestAppInstallSetup>();

  @Override
  public void setUp(TestInformation testInfo)
      throws TargetSetupError, BuildError, DeviceNotAvailableException {
    ITestDevice device = testInfo.getDevice();
    increaseSupportedUsers(device);
    Set<Integer> displayIds = device.listDisplayIdsForStartingVisibleBackgroundUsers();
    for (Integer displayId : displayIds) {
      int userId = createAndStartUser(device, displayId);
      LogUtil.CLog.d(
          "Created and started new passenger user: %s on Display: %s", userId, displayId);
      mDisplayToCreatedUsers.put(displayId, userId);
    }
    skipGtos(device);
    skipSuw(device);
    dismissChromeDialogs(device);

    if (!skipLoading && mInstallYTApk) {
      installApk(testInfo);
    }

    for (Integer displayId : mDisplayToCreatedUsers.keySet()) {
      if (mSkipDisplayIds.contains(displayId)) {
        LogUtil.CLog.d("Skipping load on display %d", displayId);
        continue;
      }
      int userId = mDisplayToCreatedUsers.get(displayId);
      simulatePassengerLoad(device, userId);
    }
  }

  @Override
  public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
    ITestDevice device = testInfo.getDevice();
    if (!skipLoading) {
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

  private void stopTestApps(ITestDevice device) throws DeviceNotAvailableException {
    LogUtil.CLog.d("Stopping the Youtube application for all the passengers");
    for (int userID : mDisplayToCreatedUsers.values()) {
      String stopYoutube = String.format("am force-stop --user %d %s", userID, mPackage);
      String stopChrome = String.format("am force-stop --user %d com.chrome.beta", userID);
      CommandResult stopYoutubeResult = device.executeShellV2Command(stopYoutube);
      CommandResult stopChromeResult = device.executeShellV2Command(stopChrome);
      if (stopYoutubeResult.getExitCode() != 0 || stopChromeResult.getExitCode() != 0) {
        LogUtil.CLog.d("Failed to kill the Youtube application for user: %d", userID);
      }
    }
  }

  private void dismissChromeDialogs(ITestDevice device) throws DeviceNotAvailableException {
    LogUtil.CLog.d("Dismissing initial Chrome Dialogs");
    String dismissCommand = "am set-debug-app --persistent com.chrome.beta";
    CommandResult dismissResult = device.executeShellV2Command(dismissCommand);
    if (dismissResult.getExitCode() != 0) {
      LogUtil.CLog.d("Failed to dismiss Chrome dialogs");
    }
    LogUtil.CLog.d("Successfully dismissed initial Chrome Dialogs");
  }

  private void increaseSupportedUsers(ITestDevice device)
      throws TargetSetupError, DeviceNotAvailableException {
    LogUtil.CLog.d("Temporarily increasing maximum supported users to " + maxUsers);
    String setMaxUsers = "setprop fw.max_users " + maxUsers;
    CommandResult setMaxUsersResult = device.executeShellV2Command(setMaxUsers);
    if (!CommandStatus.SUCCESS.equals(setMaxUsersResult.getStatus())) {
      throw new TargetSetupError(
          "Failed to increase the number of supported users", device.getDeviceDescriptor());
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
    LogUtil.CLog.d(String.format("Started background user %d for display %d", userId, displayId));
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
          String.format("Installing the following test APKs in user %d: \n%s", userId, mTestFiles));
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
    LogUtil.CLog.d(
        String.format("Launching the Youtube App for User: %d with url: %s", userId, mUrl));
    String launchYoutubeWithUrlCommand =
        String.format(
            "am start --user %d -a android.intent.action.VIEW -e FullScreen true  -d "
                + "\"%s\" %s",
            userId, mUrl, mPackage);
    LogUtil.CLog.d("Youtube launch command: %s", launchYoutubeWithUrlCommand);
    CommandResult result = device.executeShellV2Command(launchYoutubeWithUrlCommand);
    if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
      throw new TargetSetupError(
          String.format("Failed to launch the Youtube app for the user %d", userId),
          device.getDeviceDescriptor());
    }
    LogUtil.CLog.d("Successfully launched the Youtube video for user: %d", userId);
  }

  // Skips the Set-up wizard for all the passenger users.
  private void skipSuw(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
    LogUtil.CLog.d("Skipping set-up wizard for all passenger users");
    for (int userID : mDisplayToCreatedUsers.values()) {
      String suwSkipCommand =
          String.format(
              "am start --user %d -n com.google.android.car.setupwizard/.ExitActivity", userID);
      CommandResult suwSkipCommandResult = device.executeShellV2Command(suwSkipCommand);
      if (suwSkipCommandResult.getExitCode() != 0) {
        throw new TargetSetupError(
            String.format("Failed to skip the set-up wizard for user: %d", userID),
            device.getDeviceDescriptor());
      }
    }
    LogUtil.CLog.d("Successfully skipped set-up wizard across all passenger users");
  }

  private int getCurrentUser(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
    LogUtil.CLog.d("Getting the current user ID");
    String getCurrentUserCommand = "am get-current-user";
    CommandResult getCurrentUserCommandResult = device.executeShellV2Command(getCurrentUserCommand);
    if (getCurrentUserCommandResult.getExitCode() != 0) {
      throw new TargetSetupError(
          String.format("Failed to get the current user"), device.getDeviceDescriptor());
    }
    return Integer.parseInt(getCurrentUserCommandResult.getStdout().trim());
  }

  // Skips the Google Terms and Conditions for all the users. This would remove the restrictions
  // enforced on GAS apps for all users.
  private void skipGtos(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
    LogUtil.CLog.d("Skipping gTOS on behalf of all users");
    if (!device.isAdbRoot()) {
      device.enableAdbRoot();
    }
    List<String> gasPackageNames =
        Arrays.asList(
            "com.google.android.apps.maps",
            "com.android.vending",
            "com.google.android.carassistant",
            mPackage,
            "com.chrome.beta");
    Map<Integer, Integer> mAllDisplaysToCreatedUsers = new HashMap<>();
    mAllDisplaysToCreatedUsers.putAll(mDisplayToCreatedUsers);
    mAllDisplaysToCreatedUsers.put(0, getCurrentUser(device));
    for (int userID : mAllDisplaysToCreatedUsers.values()) {
      for (String gasPackageName : gasPackageNames) {
        String gTOSPmCommand = String.format("pm enable --user %d %s ", userID, gasPackageName);
        CommandResult gTOSPmResult = device.executeShellV2Command(gTOSPmCommand);
        if (gTOSPmResult.getExitCode() != 0) {
          throw new TargetSetupError(
              String.format(
                  "Failed to skip gTOS for user: %d and package: %s", userID, gasPackageName),
              device.getDeviceDescriptor());
        }
      }
      String gTOSKeyUserCommand =
          String.format(
              "settings put secure --user %d android.car.KEY_USER_TOS_ACCEPTED 2 ", userID);
      CommandResult gTOSKeyUserResult = device.executeShellV2Command(gTOSKeyUserCommand);
      if (gTOSKeyUserResult.getExitCode() != 0) {
        throw new TargetSetupError(
            String.format("Failed to accept gTOS for user: %d", userID),
            device.getDeviceDescriptor());
      }
    }
    LogUtil.CLog.d("Successfully skipped gTOS across all passenger users");
  }
}
