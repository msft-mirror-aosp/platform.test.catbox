
// Copyright 2023 Google Inc. All Rights Reserved.
package com.google.android.car.aaosbt;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.TargetSetupError;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IRemoteTest;
import java.net.URISyntaxException;
import com.android.tradefed.device.ITestDevice;

@OptionClass(alias = "aaos-moped-test")
public class MopedRunner implements IRemoteTest {
    @Option(name = "test-artifact", description = "test artifact")
    private File test_artifact = null;

    @Option(name = "artifact", description = "test artifact")
    private String artifact_str = null;

    @Option(name = "unzip-build-timeout-min", description = "unzip build timeout in minutes")
    private int unzip_build_timeout_min = 10;

    @Option(name = "test-timeout-min", description = "test timeout in minutes")
    private int test_timeout_min = 60;

    private File mLocalDestFile;
    private File mLocalSrcFile;
    private String mArtifactLocation;

    private void unTarTestArtifact(TestInformation testInfo) throws TargetSetupError, TimeoutException, URISyntaxException {
        if (test_artifact != null && artifact_str == null) {
            artifact_str = test_artifact.toPath().toString();
        }
        String jarPath = getClass()
          .getProtectionDomain()
          .getCodeSource()
          .getLocation()
          .toURI()
          .getPath();
        File jarFile = new File(jarPath);
        mLocalSrcFile = new File(jarFile.getParent() + "/../testcases/" + artifact_str);
        mLocalDestFile = new File(testInfo.dependenciesFolder().toString());
        mArtifactLocation =
                mLocalDestFile.getPath()
                        + "/"
                        + mLocalSrcFile.getName().replaceAll(".tar.*gz", "/");
        Path localArtifactPath = Paths.get(mArtifactLocation);
        if (!Files.exists(localArtifactPath)) {
            // untar file
            executeHostCommand(
                    new String[] {
                        "bash",
                        "-c",
                        "tar xf " + mLocalSrcFile.getPath() + " -C " + mLocalDestFile.getPath()
                    },
                    unzip_build_timeout_min);
        }
    }

    private String getDevicesString(TestInformation testInfo) {
      String deviceString = "";
      for(ITestDevice device : testInfo.getDevices()) {
        deviceString += " " + device.getSerialNumber();
      }
      return deviceString;
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        // Download Moped binanry / config and run test
        try {
            unTarTestArtifact(testInfo);
            executeHostCommand(
                    new String[] {"bash", "-c", "bash " + mArtifactLocation + "run.sh"+getDevicesString(testInfo)},
                    test_timeout_min);
        } catch (TargetSetupError e) {
            CLog.logAndDisplay(LogLevel.VERBOSE, "There are problems running tests! %s", e);
        } catch (TimeoutException e) {
            CLog.logAndDisplay(LogLevel.VERBOSE, "Test execution timeout! %s", e);
        } catch (URISyntaxException e) {
            CLog.logAndDisplay(LogLevel.VERBOSE, "Test artifact not found! %s", e);
        }
    }

    private ArrayList<String> executeHostCommand(String[] command, int timeout)
            throws TargetSetupError, TimeoutException {
        ArrayList<String> ret = new ArrayList<String>();
        try {
            CLog.logAndDisplay(
                    LogLevel.VERBOSE, "Output of running %s is:", Arrays.toString(command));
            Process p = Runtime.getRuntime().exec(command);
            if (!p.waitFor(timeout, TimeUnit.MINUTES)) {
                p.destroy();
                throw new TimeoutException();
            }
            InputStream is = p.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ((line = br.readLine()) != null) {
                CLog.logAndDisplay(LogLevel.VERBOSE, line);
                ret.add(line);
            }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new TargetSetupError(
                        "Execution of command " + Arrays.toString(command) + " failed!");
            }
        } catch (IOException e) {
            CLog.logAndDisplay(LogLevel.VERBOSE, "There are problems with IO! %s", e);
        } catch (InterruptedException e) {
            CLog.logAndDisplay(LogLevel.VERBOSE, "User interruption!");
        }
        return ret;
    }
}

