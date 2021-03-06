/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthings.internal.handler;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.smartthings.internal.SmartthingsBindingConstants;
import org.openhab.binding.smartthings.internal.SmartthingsHandlerFactory;
import org.openhab.binding.smartthings.internal.converter.SmartthingsConverter;
import org.openhab.binding.smartthings.internal.dto.SmartthingsStateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Bob Raker - Initial contribution
 */
@NonNullByDefault
public class SmartthingsThingHandler extends ConfigStatusThingHandler {

    private Logger logger = LoggerFactory.getLogger(SmartthingsThingHandler.class);

    private SmartthingsThingConfig config;
    private String smartthingsName;
    private SmartthingsHandlerFactory smartthingsHandlerFactory;
    private Map<ChannelUID, SmartthingsConverter> converters = new HashMap<ChannelUID, SmartthingsConverter>();

    public SmartthingsThingHandler(Thing thing, SmartthingsHandlerFactory smartthingsHandlerFactory) {
        super(thing);
        this.smartthingsHandlerFactory = smartthingsHandlerFactory;
        smartthingsName = ""; // Initialize here so it can be NonNull but it should always get a value in initialize()
        config = getThing().getConfiguration().as(SmartthingsThingConfig.class);
    }

    /**
     * Called when openHAB receives a command for this handler
     *
     * @param channelUID The channel the command was sent to
     * @param command The command sent
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        Bridge bridge = getBridge();

        // Check if the bridge has not been initialized yet
        if (bridge == null) {
            logger.debug(
                    "The bridge has not been initialized yet. Can not process command for channel {} with command {}.",
                    channelUID.getAsString(), command.toFullString());
            return;
        }

        SmartthingsBridgeHandler smartthingsBridgeHandler = (SmartthingsBridgeHandler) bridge.getHandler();
        if (smartthingsBridgeHandler != null
                && smartthingsBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            String thingTypeId = thing.getThingTypeUID().getId();
            String smartthingsType = getSmartthingsAttributeFromChannel(channelUID);

            SmartthingsConverter converter = converters.get(channelUID);

            String path;
            String jsonMsg;
            if (command instanceof RefreshType) {
                path = "/state";
                // Go to ST hub and ask for current state
                jsonMsg = String.format(
                        "{\"capabilityKey\": \"%s\", \"deviceDisplayName\": \"%s\", \"capabilityAttribute\": \"%s\", \"openHabStartTime\": %d}",
                        thingTypeId, smartthingsName, smartthingsType, System.currentTimeMillis());
            } else {
                // Send update to ST hub
                path = "/update";
                jsonMsg = converter.convertToSmartthings(channelUID, command);

                // The smartthings hub won't (can't) return a response to this call. But, it will send a separate
                // message back to the SmartthingBridgeHandler.receivedPushMessage handler
            }

            try {
                smartthingsHandlerFactory.sendDeviceCommand(path, jsonMsg);
                // Smartthings will not return a response to this message but will send it's response message
                // which will get picked up by the SmartthingBridgeHandler.receivedPushMessage handler
            } catch (InterruptedException | TimeoutException | ExecutionException e) {
                logger.info("Attempt to send command to the Smartthings hub failed with exception", e);
            }
        }
    }

    /**
     * Get the Smartthings capability reference "attribute" from the channel properties.
     * In OpenHAB each channel id corresponds to the Smartthings attribute. In the ChannelUID the
     * channel id is the last segment
     *
     * @param channelUID
     * @return channel id
     */
    private String getSmartthingsAttributeFromChannel(ChannelUID channelUID) {
        String id = channelUID.getId();
        return id;
    }

    /**
     * State messages sent from the hub arrive here, are processed and the openHab state is updated.
     *
     * @param stateData
     */
    public void handleStateMessage(SmartthingsStateData stateData) {
        // First locate the channel
        Channel matchingChannel = null;
        for (Channel ch : thing.getChannels()) {
            if (ch.getUID().getAsString().endsWith(stateData.capabilityAttribute)) {
                matchingChannel = ch;
                break;
            }
        }
        if (matchingChannel == null) {
            return;
        }

        SmartthingsConverter converter = converters.get(matchingChannel.getUID());

        // If value from Smartthings is null then stop here
        State state;
        if (stateData.value != null) {
            state = converter.convertToOpenHab(matchingChannel.getAcceptedItemType(), stateData);
        } else {
            state = UnDefType.NULL;
        }

        updateState(matchingChannel.getUID(), state);
        logger.debug("Smartthings updated State for channel: {} to {}", matchingChannel.getUID().getAsString(),
                state.toString());

        // Output timing information
        long openHabTime = (stateData.openHabStartTime > 0) ? System.currentTimeMillis() - stateData.openHabStartTime
                : 0;
        logger.debug("State timing data, Request time until data received and processed {}, Hub processing time: {} ",
                openHabTime, stateData.hubTime);
    }

    @Override
    public void initialize() {
        if (!validateConfig(this.config)) {
            return;
        }
        smartthingsName = config.smartthingsName;

        // Create converters for each channel
        for (Channel ch : thing.getChannels()) {
            @Nullable
            String converterName = ch.getProperties().get("smartthings-converter"); // Will be null if no explicit
                                                                                    // converter was specified
            if (converterName == null || converterName.length() == 0) {
                // A converter was Not specified so use the channel id
                converterName = ch.getUID().getId();
            }

            // Try to get the converter
            @Nullable
            SmartthingsConverter cvtr = getConverter(converterName);
            if (cvtr == null) {
                // If there is no channel specific converter the get the "default" converter
                cvtr = getConverter("default");
            }

            if (cvtr != null) {
                // cvtr should never be null because there should always be a "default" converter
                converters.put(ch.getUID(), cvtr);
            }

        }

        updateStatus(ThingStatus.ONLINE);
    }

    private @Nullable SmartthingsConverter getConverter(String converterName) {
        // Converter name will be a name such as "switch" which has to be converted into the full class name such as
        // org.openhab.binding.smartthings.internal.converter.SmartthingsSwitchConveter
        StringBuffer converterClassName = new StringBuffer(
                "org.openhab.binding.smartthings.internal.converter.Smartthings");
        converterClassName.append(Character.toUpperCase(converterName.charAt(0)));
        converterClassName.append(converterName.substring(1));
        converterClassName.append("Converter");
        try {
            Constructor<?> constr = Class.forName(converterClassName.toString()).getDeclaredConstructor(Thing.class);
            constr.setAccessible(true);
            SmartthingsConverter cvtr = (SmartthingsConverter) constr.newInstance(thing);
            logger.debug("Using converter {}", converterName);
            return cvtr;
        } catch (ClassNotFoundException e) {
            // Most of the time there is no channel specific converter, the default converter is all that is needed.
            logger.trace("No Custom converter exists for {} ({})", converterName, converterClassName);
        } catch (NoSuchMethodException e) {
            logger.info("NoSuchMethodException occurred for {} ({}) {}", converterName, converterClassName,
                    e.getMessage());
        } catch (InvocationTargetException e) {
            logger.info("InvocationTargetException occurred for {} ({}) {}", converterName, converterClassName,
                    e.getMessage());
        } catch (IllegalAccessException e) {
            logger.info("IllegalAccessException occurred for {} ({}) {}", converterName, converterClassName,
                    e.getMessage());
        } catch (InstantiationException e) {
            logger.info("InstantiationException occurred for {} ({}) {}", converterName, converterClassName,
                    e.getMessage());
        }
        return null;
    }

    /**
     * Handle an update to the configuration
     */
    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        logger.debug("Received configuration update for thing: {}", thing.getUID().getAsString());

        boolean configChanged = false;

        Configuration configuration = editConfiguration();
        // Examine each new config parameter and if it is different than the existing then update it
        for (Entry<String, Object> configurationParameter : configurationParameters.entrySet()) {
            String paramName = configurationParameter.getKey();
            Object valueObject = configurationParameter.getValue();
            Object existingValue = configuration.get(paramName);

            // Only 2 parameters to check and verify: smartthingsName and smartthingsLocation which is optional
            if (paramName.equals("smartthingsName")) {
                if (!(valueObject instanceof String)) {
                    logger.info(
                            "Configuration update of smartthingsName for {} is not valid because the new value is missing or is not a String, change ignored",
                            thing.getUID().getAsString());
                    return;
                }

                String valueString = (String) valueObject;
                if (valueString.length() == 0) {
                    logger.info(
                            "Configuration update of smartthingsName for {} is not valid because the new value is 0 length, change ignored",
                            thing.getUID().getAsString());
                    return;
                }

                if (!existingValue.equals(valueString)) {
                    // Found a change
                    configuration.put("smartthingsName", valueString);
                    configChanged = true;
                    logger.info("Configuration updated for {} smartthingsName changed from {} to {}",
                            thing.getUID().getAsString(), existingValue, valueString);
                }
            }

            if (paramName.equals("smartthingsLocation")) {
                if (!(valueObject instanceof String) && (existingValue == null || !(existingValue instanceof String))) {
                    // OK No change
                    return;
                }

                String valueString = (String) valueObject;
                if (valueString.equals(existingValue)) {
                    // OK No change
                    return;
                }

                // Found a change
                configuration.put("smartthingsLocation", valueString);
                configChanged = true;
                logger.info("Configuration updated for {} smartthingsLocation changed from {} to {}",
                        thing.getUID().getAsString(), existingValue, valueString);
            }
        }

        if (configChanged) {
            // Persist changes
            updateConfiguration(configuration);
        }
    }

    @Override
    public void dispose() {
        logger.info("Disposing of the Smartthings Thing Handler");
    }

    private boolean validateConfig(SmartthingsThingConfig config) {
        String name = config.smartthingsName;
        if (name.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Smartthings device name is missing");
            return false;
        }

        return true;
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        Collection<ConfigStatusMessage> configStatusMessages = new LinkedList<ConfigStatusMessage>();

        // The name must be provided
        String stName = config.smartthingsName;
        if (stName.isEmpty()) {
            configStatusMessages.add(ConfigStatusMessage.Builder.error(SmartthingsBindingConstants.SMARTTHINGS_NAME)
                    .withMessageKeySuffix(SmartthingsThingConfigStatusMessage.SMARTTHINGS_NAME_MISSING)
                    .withArguments(SmartthingsBindingConstants.SMARTTHINGS_NAME).build());
        }

        return configStatusMessages;
    }

    public String getSmartthingsName() {
        return smartthingsName;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("smartthingsName :").append(smartthingsName);
        sb.append(", thing UID: ").append(this.thing.getUID());
        sb.append(", thing label: ").append(this.thing.getLabel());
        return sb.toString();
    }

}
