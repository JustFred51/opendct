/*
 * Copyright 2015 The OpenDCT Authors. All Rights Reserved
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

package opendct.tuning.http;

import opendct.channel.ChannelManager;
import opendct.channel.TVChannel;
import opendct.config.Config;
import opendct.tuning.discovery.discoverers.UpnpDiscoverer;
import opendct.util.ThreadPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.concurrent.Future;

public class InfiniTVTuning {
    private static final Logger logger = LogManager.getLogger(InfiniTVTuning.class);

    public static boolean isValidModulation(TVChannel tvChannel) {
        return tvChannel.getModulation().equalsIgnoreCase("QAM") ||
                tvChannel.getModulation().equalsIgnoreCase("QAM256") ||
                tvChannel.getModulation().equalsIgnoreCase("QAM64") ||
                tvChannel.getModulation().equalsIgnoreCase("NTSC-M") ||
                tvChannel.getModulation().equalsIgnoreCase("8VSB");
    }

    public static boolean tuneChannel(String lineupName, String channel, String deviceAddress, int tunerNumber, boolean useVChannel, int retry) throws InterruptedException {

        if (!useVChannel) {
            TVChannel tvChannel = ChannelManager.getChannel(lineupName, channel);
            return tuneQamChannel(tvChannel, deviceAddress, tunerNumber, retry);
        }

        // There is no need to look up the channel when a CableCARD is present.
        return tuneVChannel(channel, deviceAddress, tunerNumber, retry);
    }

    public static boolean tuneQamChannel(TVChannel tvChannel, String deviceAddress, int tunerNumber, int retry) throws InterruptedException {
        logger.entry(deviceAddress, tunerNumber);

        boolean returnValue = false;

        if (tvChannel == null) {
            logger.error("The requested channel does not exist in the channel map.");
            return logger.exit(false);
        }

        try {
            // Check if the frequency is already correct.
            String currentFrequency = InfiniTVStatus.getVar(deviceAddress, tunerNumber, "tuner", "Frequency") + "000";

            boolean frequencyTuned = currentFrequency.equals(String.valueOf(tvChannel.getFrequency()));
            int attempts = 20;

            String desiredFrequency = String.valueOf(tvChannel.getFrequency());
            while (!frequencyTuned) {
                tuneFrequency(tvChannel, deviceAddress, tunerNumber, retry);

                // InfiniTV provides the frequency in kHz, so we need 3 more zeros.
                currentFrequency = InfiniTVStatus.getVar(deviceAddress, tunerNumber, "tuner", "Frequency") + "000";
                frequencyTuned = currentFrequency.equals(desiredFrequency);

                if (attempts-- == 0 && !frequencyTuned) {
                    logger.error("The requested frequency cannot be tuned.");
                    return logger.exit(false);
                } else if (!frequencyTuned) {
                    try {
                        // Sleep if the first request fails so we don't overwhelm the device
                        // with requests. Remember up to 6 of these kinds of request could
                        // happen at the exact same time.
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        logger.error("tuneChannel was interrupted while tuning to a frequency.");
                        return logger.exit(false);
                    }
                }
            }

            attempts = 20;
            boolean programSelected = false;

            Thread.sleep(250);

            String desiredProgram = String.valueOf(tvChannel.getProgram());
            while (!programSelected) {
                // If we are not already on the correct frequency, it takes the tuner a moment
                // to detect the available programs. If you try to set a program before the list
                // is available, it will fail. Normally this happens so fast, a sleep method
                // isn't appropriate. We have a while loop to retry a few times if it fails.

                tuneProgram(tvChannel, deviceAddress, tunerNumber, retry);

                programSelected = InfiniTVStatus.getVar(deviceAddress, tunerNumber, "mux", "ProgramNumber").equals(desiredProgram);
                if (attempts-- == 0 && !programSelected) {
                    logger.error("The requested program cannot be selected.");
                    return logger.exit(false);
                } else if (!programSelected) {
                    try {
                        // Sleep if the first request fails so we don't overwhelm the device
                        // with requests. Remember up to 6 of these kinds of request could
                        // happen at the exact same time.
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        logger.error("tuneChannel was interrupted while selecting a program.");
                        return logger.exit(false);
                    }
                }
            }
            returnValue = true;
        /*} catch (InterruptedException e) {
            logger.debug("tuneChannel was interrupted while waiting setting the program.");*/
        } catch (IOException e) {
            logger.debug("tuneChannel was unable to get the current program value.");
            UpnpDiscoverer.requestBroadcast();
        }


        return logger.exit(returnValue);
    }

    public static boolean tuneVChannel(String vchannel, String deviceAddress, int tunerNumber, int retry) throws InterruptedException {
        logger.entry(vchannel, deviceAddress, tunerNumber);

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }
        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String channel = "channel=" + vchannel;

        boolean returnValue = postContent(deviceAddress, "/channel_request.cgi", retry, instanceId,
                channel);

        return logger.exit(returnValue);
    }

    public static boolean tuneVChannel(TVChannel tvChannel, String deviceAddress, int tunerNumber, int retry) throws InterruptedException {
        logger.entry(tvChannel, deviceAddress, tunerNumber);

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }
        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String channel = "channel=" + tvChannel.getChannel();

        boolean returnValue = postContent(deviceAddress, "/channel_request.cgi", retry, instanceId,
                channel);

        return logger.exit(returnValue);
    }

    public static boolean tuneFrequency(TVChannel tvChannel, String deviceAddress, int tunerNumber, int retry) throws InterruptedException {
        logger.entry(tvChannel, deviceAddress, tunerNumber);

        logger.info("Tuning frequency '{}'.", tvChannel.getFrequency());

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }
        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String frequency = "frequency=" + (tvChannel.getFrequency() / 1000);

        int tryModulation = -1;
        String modulation;
        if (tvChannel.getModulation().equalsIgnoreCase("QAM256")) {
            modulation = "modulation=2";
        } else if (tvChannel.getModulation().equalsIgnoreCase("QAM64")) {
            modulation = "modulation=0";
        } else if (tvChannel.getModulation().equalsIgnoreCase("NTSC-M")) {
            modulation = "modulation=4";
        } else if (tvChannel.getModulation().equalsIgnoreCase("8VSB")) {
            modulation = "modulation=6";
        } else {
            logger.info("Trying QAM64 modulation...");
            modulation = "modulation=0";
            tryModulation = 0;
        }

        String tuner = "tuner=1";
        String demod = "demod=1";
        String rstChnl = "rst_chnl=0";
        String forceTune = "force_tune=0";

        boolean returnValue = postContent(deviceAddress, "/tune_request.cgi", retry, instanceId,
                frequency, modulation, tuner, demod, rstChnl, forceTune);

        while (tryModulation != -1) {
            returnValue = false;
            int timeout = 20;
            while (!returnValue && timeout-- > 0) {
                tuneProgram(tvChannel, deviceAddress, tunerNumber, 5);
                Thread.sleep(200);
                try {
                    returnValue = InfiniTVStatus.getProgram(deviceAddress, tunerNumber, 2) == tvChannel.getProgram();
                } catch (IOException e) {}
            }
            // This worked, so store the good value.
            if (returnValue) {
                tvChannel.setModulation(tryModulation == 0 ? "QAM64" : "QAM256");
                break;
            } else if (tryModulation == 2) {
                break;
            }

            logger.info("Trying QAM256 modulation...");
            modulation = "modulation=2";
            tryModulation = 2;

            postContent(deviceAddress, "/tune_request.cgi", retry, instanceId,
                    frequency, modulation, tuner, demod, rstChnl, forceTune);
        }

        return logger.exit(returnValue);
    }

    public static boolean tuneProgram(TVChannel tvChannel, String deviceAddress, int tunerNumber, int retry) throws InterruptedException {
        logger.entry(deviceAddress, deviceAddress, tunerNumber);

        logger.info("Selecting program '{}'.", tvChannel.getProgram());

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }
        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String program = "program=" + tvChannel.getProgram();

        boolean returnValue = postContent(deviceAddress, "/program_request.cgi", retry, instanceId,
                program);

        return logger.exit(returnValue);
    }

    public static boolean startRTSP(String localIPAddress, int rtpStreamLocalPort, String deviceAddress, int tunerNumber) {
        logger.entry(localIPAddress, rtpStreamLocalPort, deviceAddress, tunerNumber);

        logger.info("Starting streaming from tuner number {} to local port '{}'.", tunerNumber, rtpStreamLocalPort);

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }

        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String destIp = "dest_ip=" + localIPAddress;
        String destPort = "dest_port=" + rtpStreamLocalPort;
        String protocol = "protocol=0"; //RTP
        String start = "start=1"; // 1 = Started (0 = Stopped)

        boolean returnValue = postContent(deviceAddress, "/stream_request.cgi", instanceId,
                destIp, destPort, protocol, start);

        return logger.exit(returnValue);
    }

    public static boolean stopRTSP(String deviceAddress, int tunerNumber) {
        logger.entry(deviceAddress, tunerNumber);

        logger.info("Stopping streaming from tuner number {} at '{}'.", tunerNumber, deviceAddress);

        if (tunerNumber - 1 < 0) {
            logger.error("The tuner number cannot be less than 1.");
            return logger.exit(false);
        }

        String instanceId = "instance_id=" + String.valueOf(tunerNumber - 1);

        String destIp = "dest_ip=192.168.200.2";
        String destPort = "dest_port=8000";
        String protocol = "protocol=0"; //RTP
        String start = "start=0"; // 0 = Stopped (1 = Started)

        boolean returnValue = postContent(deviceAddress, "/stream_request.cgi", instanceId,
                destIp, destPort, protocol, start);

        return logger.exit(returnValue);
    }

    public static boolean postContent(String deviceAddress, String postPath, int retry, String... parameters) throws InterruptedException {
        retry = Math.abs(retry) + 1;

        for (int i = 0; i < retry; i++) {
            if (postContent(deviceAddress, postPath, parameters)) {
                return logger.exit(true);
            }
            
            logger.error("Unable to access device '{}', attempt number {}", deviceAddress, i);
            UpnpDiscoverer.requestBroadcast();
            Thread.sleep(200);
        }

        return logger.exit(false);
    }

    public static boolean postContent(String deviceAddress, String postPath, String... parameters) {
        logger.entry(deviceAddress, postPath, parameters);

        StringBuilder postParameters = new StringBuilder();
        for (String parameter : parameters) {
            postParameters.append(parameter);
            postParameters.append("&");
        }

        if (postParameters.length() > 0) {
            postParameters.deleteCharAt(postParameters.length() - 1);
        }

        byte postBytes[] = postParameters.toString().getBytes(Config.STD_BYTE);

        URL url;
        try {
            url = new URL("http://" + deviceAddress + postPath);
        } catch (MalformedURLException e) {
            logger.error("Unable to create a valid URL using 'http://{}{}'", deviceAddress, postPath);
            return logger.exit(false);
        }
        logger.info("Connecting to InfiniTV tuner using the URL '{}'", url);

        final HttpURLConnection httpURLConnection;
        try {
            httpURLConnection = (HttpURLConnection) url.openConnection();
        } catch (IOException e) {
            logger.error("Unable to open an HTTP connection => {}", e);
            UpnpDiscoverer.requestBroadcast();
            return logger.exit(false);
        }

        httpURLConnection.setDoOutput(true);

        try {
            httpURLConnection.setRequestMethod("POST");
        } catch (ProtocolException e) {
            logger.error("Unable to change request method to POST => {}", e);
            return logger.exit(false);
        }

        httpURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        httpURLConnection.setRequestProperty("charset", "utf-8");
        httpURLConnection.setRequestProperty("Content-Length", String.valueOf(postBytes.length));

        OutputStream dataOutputStream;
        try {
            httpURLConnection.connect();
            dataOutputStream = httpURLConnection.getOutputStream();
            dataOutputStream.write(postBytes);
        } catch (IOException e) {
            logger.error("Unable to write POST bytes => {}", e);
            UpnpDiscoverer.requestBroadcast();
            return logger.exit(false);
        }

        try {
            dataOutputStream.close();

            final HttpURLConnection finalHttpURLConnection = httpURLConnection;

            Future httpTimeout = ThreadPool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }

                    UpnpDiscoverer.requestBroadcast();
                    finalHttpURLConnection.disconnect();
                }
            }, Thread.MIN_PRIORITY, "HttpTimeout", deviceAddress);

            InputStream inputStream = httpURLConnection.getInputStream();
            httpTimeout.cancel(true);

            // The InfiniTV requires that at least one byte of data is read or the POST will fail.
            if (inputStream.available() > 0) {
                inputStream.read();
            }

            inputStream.close();
        } catch (IOException e) {
            logger.error("Unable to read reply. Capture device may not be available => {}",
                    e.getMessage());
        }

        return logger.exit(true);
    }
}
