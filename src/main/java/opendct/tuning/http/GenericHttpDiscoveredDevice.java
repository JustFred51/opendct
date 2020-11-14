/*
 * Copyright 2015-2016 The OpenDCT Authors. All Rights Reserved
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

package opendct.tuning.http;

import opendct.capture.CaptureDevice;
import opendct.capture.CaptureDeviceIgnoredException;
import opendct.capture.GenericHttpCaptureDevice;
import opendct.config.Config;
import opendct.config.options.*;
import opendct.nanohttpd.pojo.JsonOption;
import opendct.tuning.discovery.BasicDiscoveredDevice;
import opendct.tuning.discovery.CaptureDeviceLoadException;
import opendct.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GenericHttpDiscoveredDevice extends BasicDiscoveredDevice {
    private final static Logger logger = LogManager.getLogger(GenericHttpDiscoveredDevice.class);

    // Pre-pend this value for saving and getting properties related to just this tuner.
    protected final String propertiesDeviceRoot;

    private final Map<String, URL> channelUrlMap = new ConcurrentHashMap<>();
    private final Map<String, URI> channelUriMap = new ConcurrentHashMap<>();
    private URL sourceUrl = null;
    private URI sourceUri = null;

    private final Map<String, DeviceOption> deviceOptions;
    private StringDeviceOption streamingUrl;
    private StringDeviceOption altStreamingUrl;
    private ChannelRangesDeviceOption altStreamingChannels;
    private StringDeviceOption pretuneExecutable;
    private StringDeviceOption tuningExecutable;
    private StringDeviceOption stoppingExecutable;
    private IntegerDeviceOption stoppingDelay;
    private IntegerDeviceOption tuningDelay;
    private StringDeviceOption customChannels;
    private IntegerDeviceOption padChannel;
    private StringDeviceOption httpUsername;
    private StringDeviceOption httpPassword;

    GenericHttpDiscoveredDeviceParent parent;

    public GenericHttpDiscoveredDevice(String name, int id, int parentId, GenericHttpDiscoveredDeviceParent parent) {
        super(name, id, parentId, "Generic HTTP Capture Device");
        this.parent = parent;

        propertiesDeviceRoot = "sagetv.device." + id + ".";
        deviceOptions = new ConcurrentHashMap<>(10);

        try {
            streamingUrl = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "streaming_url", ""),
                    false,
                    "Streaming URL",
                    propertiesDeviceRoot + "streaming_url",
                    "This is the entire URL to be read for streaming from this device."
            );

            altStreamingUrl = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "streaming_url2", ""),
                    false,
                    "Streaming URL 2",
                    propertiesDeviceRoot + "streaming_url2",
                    "This is a secondary URL that will only be used based on the" +
                            " channel being tuned. This must be a complete URL."
            );

            altStreamingChannels = new ChannelRangesDeviceOption(
                    Config.getString(propertiesDeviceRoot + "streaming_url2_channels", ""),
                    false,
                    "Streaming URL 2 Channels",
                    propertiesDeviceRoot + "streaming_url2_channels",
                    "If Streaming URL 2 is defined and channel ranges are specified here, the" +
                            " secondary URL will be used if the channel being tuned matches one" +
                            " of the ranges here."
            );

            pretuneExecutable = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "pretuning_executable", ""),
                    false,
                    "Pre-Tuning Executable",
                    propertiesDeviceRoot + "pretuning_executable",
                    "This will optionally execute every time before changing the channel. Insert" +
                            " %c% if the channel needs to be provided to the executable if %c%" +
                            " isn't provided, the channel number will not be provided."
            );

            tuningExecutable = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "tuning_executable", ""),
                    false,
                    "Tuning Executable",
                    propertiesDeviceRoot + "tuning_executable",
                    "This will optionally execute to change the channel being streamed. Insert %c%" +
                            " where the channel needs to be provided to the executable. If %c%" +
                            " isn't provided, but this property is populated, the channel number" +
                            " will be appended as a final parameter."
            );

            stoppingExecutable = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "stopping_executable", ""),
                    false,
                    "Stopping Executable",
                    propertiesDeviceRoot + "stopping_executable",
                    "This will optionally execute every time the capture device is stopped." +
                            " Insert %c% if the last channel needs to be provided to the" +
                            " executable if %c% isn't provided, the channel number will not be" +
                            " provided."
            );

            stoppingDelay = new IntegerDeviceOption(
                    Config.getInteger(propertiesDeviceRoot + "stopping_executable_delay_ms", 15000),
                    false,
                    "Stopping Executable Delay (ms)",
                    propertiesDeviceRoot + "stopping_executable_delay_ms",
                    "If this value is greater than zero, the capture device will wait for this" +
                            " much time to pass in milliseconds before executing the stopping" +
                            " executable. If a new recording starts before this timeout, the" +
                            " stopping executable will not run. This will prevent channel" +
                            " changing from doing a full stop/start with each change."
            );

            tuningDelay = new IntegerDeviceOption(
                    Config.getInteger(propertiesDeviceRoot + "tuning_delay_ms", 0),
                    false,
                    "Tuning Delay",
                    propertiesDeviceRoot + "tuning_delay_ms",
                    "This is the amount of time in milliseconds to wait after tuning a channel" +
                            " before starting to stream anything."
            );

            customChannels = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "custom_channels", ""),
                    false,
                    "Custom Channels",
                    propertiesDeviceRoot + "custom_channels",
                    "This is an optional semicolon delimited list of" +
                            " channels you want to appear in SageTV for this device. This is a" +
                            " shortcut around creating an actual OpenDCT lineup. If there are any" +
                            " values in the field, they will override the lineup assigned to this" +
                            " capture device on channel scan. This provides an easy way to add" +
                            " channels if you are not actually going to use guide data."
            );

            padChannel = new IntegerDeviceOption(
                    Config.getInteger(propertiesDeviceRoot + "channel_padding", 0),
                    false,
                    "Channel Padding",
                    propertiesDeviceRoot + "channel_padding",
                    "This is the minimum length to be passed for the %c% variable. Values shorter" +
                            " than this length will have zeros (0) appended to the left of the" +
                            " channel to make up the difference. (Ex. 8 becomes 008 if this is" +
                            " set to 3.)"
            );

            httpUsername = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "http_username", ""),
                    false,
                    "HTTP Username",
                    propertiesDeviceRoot + "http_username",
                    "If this property and HTTP Password are set, the provided username and" +
                            " password will be used for basic HTTP authenticate with the provided" +
                            " streaming URLs."
            );

            httpPassword = new StringDeviceOption(
                    Config.getString(propertiesDeviceRoot + "http_password", ""),
                    false,
                    "HTTP Password",
                    propertiesDeviceRoot + "http_password",
                    "If HTTP Username and this property are set, the provided username and" +
                            " password will be used for basic HTTP authenticate with the provided" +
                            " streaming URLs."
            );

            Config.mapDeviceOptions(
                    deviceOptions,
                    streamingUrl,
                    altStreamingUrl,
                    altStreamingChannels,
                    customChannels,
                    pretuneExecutable,
                    tuningExecutable,
                    stoppingExecutable,
                    stoppingDelay,
                    tuningDelay,
                    padChannel,
                    httpUsername,
                    httpPassword
            );

            // This information is used to ensure that the interface that will be used to
            // communicate with the device will be waited for when resuming from suspend.
            try {
                URL getRemoteIP = new URL(streamingUrl.getValue());
                parent.setRemoteAddress(InetAddress.getByName(getRemoteIP.getHost()));
            } catch (MalformedURLException e) {
                logger.debug("Unable to parse remote address => ", e);
            } catch (UnknownHostException e) {
                logger.debug("Unable to resolve remote address => ", e);
            }

            updateChannelMap();

        } catch (DeviceOptionException e) {
            logger.error("Unable to load the options for the generic HTTP capture device '{}'",
                    parent.getFriendlyName());
        }
    }

    @Override
    public CaptureDevice loadCaptureDevice() throws CaptureDeviceIgnoredException, CaptureDeviceLoadException {
        return new GenericHttpCaptureDevice(parent, this);
    }

    @Override
    public DeviceOption[] getOptions() {
        try {
            return new DeviceOption[] {
                    getDeviceNameOption(),
                    streamingUrl,
                    altStreamingUrl,
                    altStreamingChannels,
                    customChannels,
                    pretuneExecutable,
                    tuningExecutable,
                    stoppingExecutable,
                    stoppingDelay,
                    tuningDelay,
                    padChannel,
                    httpUsername,
                    httpPassword
            };
        } catch (DeviceOptionException e) {
            logger.error("Unable to build options for device => ", e);
        }

        return new DeviceOption[0];
    }

    @Override
    public void setOptions(JsonOption... deviceOptions) throws DeviceOptionException {
        for (JsonOption option : deviceOptions) {

            if (option.getProperty().equals(propertiesDeviceName)) {
                setFriendlyName(option.getValue());
                Config.setJsonOption(option);
                continue;
            }

            DeviceOption optionReference = this.deviceOptions.get(option.getProperty());

            if (optionReference == null) {
                continue;
            }

            if (optionReference.isArray()) {
                optionReference.setValue(option.getValues());
            } else {
                optionReference.setValue(option.getValue());
            }

            Config.setDeviceOption(optionReference);

            if (option.getProperty().equals(altStreamingChannels.getProperty())) {
                updateChannelMap();
            }
        }

        Config.saveConfig();
    }

    private void updateChannelMap() {
        // Update channel map.
        String tuningUrl = getAltStreamingUrl();
        channelUrlMap.clear();

        if (!Util.isNullOrEmpty(tuningUrl)) {
            try {
                URL altStreamingUrl = new URL(tuningUrl);
                String channels[] = getAltStreamingChannels();

                for (String channel : channels) {
                    channelUrlMap.put(channel, altStreamingUrl);
                }

                logger.info("The secondary URL '{}'" +
                        " will be used for the channels: {}", tuningUrl, channels);
            } catch (MalformedURLException e) {
                logger.warn("The secondary URL '{}' is not a valid URL." +
                                " Defaulting to primary URL.",
                        tuningUrl);
            }
        }
    }

    public URI getURI(String channel) throws URISyntaxException {
        URI newUri;

        if (channel != null) {
            newUri = channelUriMap.get(channel);

            if (newUri != null) {
                String externalForm = newUri.toString();
                if (externalForm.contains("%c%")) {
                    return new URI(externalForm.replace("%c%", channel));
                }
                return newUri;
            }
        }

        String loadUri = getStreamingUrl();

        try {
            if (loadUri.contains("%c%")) {
                if (channel == null) {
                    channel = "";
                }
                return new URI(loadUri.replace("%c%", channel));
            } else {
                newUri = new URI(loadUri);
                sourceUri = newUri;
            }
        } catch (URISyntaxException e) {
            if (sourceUri == null && !loadUri.contains("%c%")) {
                if (channel == null) {
                    channel = "";
                }
                loadUri = loadUri.replace("%c%", channel);
                throw new URISyntaxException(loadUri,
                        "Unable to start capture device because '" +
                                loadUri + "' is not a valid URI.");
            }
        }

        return sourceUri;
    }

    public URL getURL(String channel) throws MalformedURLException {
        URL newUrl;

        if (channel != null) {
            newUrl = channelUrlMap.get(channel);

            if (newUrl != null) {
                String externalForm = newUrl.toExternalForm();
                if (externalForm.contains("%c%")) {
                    return new URL(externalForm.replace("%c%", channel));
                }
                return newUrl;
            }
        }

        String loadUrl = getStreamingUrl();

        try {
            if (loadUrl.contains("%c%")) {
                if (channel == null) {
                    channel = "";
                }
                return new URL(loadUrl.replace("%c%", channel));
            } else {
                newUrl = new URL(loadUrl);
                sourceUrl = newUrl;
            }
        } catch (MalformedURLException e) {
            if (sourceUrl == null && !loadUrl.contains("%c%")) {
                if (channel == null) {
                    channel = "";
                }
                loadUrl = loadUrl.replace("%c%", channel);

                throw new MalformedURLException(
                        "Unable to start capture device because '" +
                                loadUrl + "' is not a valid URL.");
            }
        }

        return sourceUrl;
    }

    public String getTuningExecutable() {
        return tuningExecutable.getValue();
    }

    public String getStreamingUrl() {
        return streamingUrl.getValue();
    }

    public int getTuningDelay() {
        return tuningDelay.getInteger();
    }

    public String getPretuneExecutable() {
        return pretuneExecutable.getValue();
    }

    public String getStoppingExecutable() {
        return stoppingExecutable.getValue();
    }

    public int getStoppingDelay() {
        return stoppingDelay.getInteger();
    }

    public String getCustomChannels() {
        return customChannels.getValue();
    }

    public int getPadChannel() {
        return padChannel.getInteger();
    }

    public String getAltStreamingUrl() {
        return altStreamingUrl.getValue();
    }

    public String[] getAltStreamingChannels() {
        return ChannelRangesDeviceOption.parseRanges(altStreamingChannels.getValue());
    }

    public String getHttpUsername() {
        return httpUsername.getValue();
    }

    public String getHttpPassword() {
        return httpPassword.getValue();
    }
}
