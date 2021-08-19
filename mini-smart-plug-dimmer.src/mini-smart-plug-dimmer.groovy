/**
 *      Min Smart Plug Dimmer v1.1.9(HUBITAT)
 *
 *  	Models: MINOSTON (MP21ZD MP22ZD/ZW39S ZW96SD)
 *
 *  Author:
 *   winnie (sky-nie)
 *
 *	Documentation:
 *
 *  Changelog:
 *
 *    1.1.9 (07/29/2021)
 *      - add a fingerprint for a new device
 *
 *    1.1.8 (07/22/2021)
 *      - remove code about "Temperature Measurement" as beta product.
 *      - change "auto off interval"  and "auto on interval" 's range
 *
 *    1.1.7 (07/22/2021)
 *      - fix a bug about temperature report threshold sync.
 *
 *    1.1.6 (07/13/2021)
 *      - Syntax format compliance adjustment
 *      - Adjust the preferences interface prompts for SmartTings App
 *      - Simplify the process of calling sendHubCommand
 *
 *    1.1.5 (07/13/2021)
 *      - Syntax format compliance adjustment
 *      - delete dummy code
 *
 *    1.1.4 (07/12/2021)
 *    1.1.3 (07/07/2021)
 *      - delete dummy code
 *
 *    1.1.2 (06/30/2021)
 *      - Add new product supported
 *
 *    1.1.1 (05/06/2021)
 *      - 1.Solve the problem that the temperature cannot be displayed normally
 *      - 2.Synchronize some of the latest processing methods, refer to Minoston Door/Window Sensor
 *
 *    1.0.1 (03/17/2021)
 *      - Simplify the code, delete dummy code
 *
 *    1.0.0 (03/11/2021)
 *      - Initial Release
 *
 * Reference：
 *    https://github.com/krlaframboise/SmartThings/blob/master/devicetypes/krlaframboise/eva-logik-in-wall-smart-dimmer.src/eva-logik-in-wall-smart-dimmer.groovy
 *    https://github.com/krlaframboise/SmartThings/blob/master/devicetypes/krlaframboise/aeotec-trisensor.src/aeotec-trisensor.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

metadata {
    definition (
            name: "Mini Smart Plug Dimmer",
            namespace: "sky-nie",
            author: "winnie",
            importUrl: "https://raw.githubusercontent.com/sky-nie/hubitat/main/mini-smart-plug-dimmer.src/mini-smart-plug-dimmer.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "Switch Level"
        capability "Configuration"
        capability "Refresh"

        attribute "firmwareVersion", "string"
        attribute "lastCheckIn", "string"
        attribute "syncStatus", "string"

        fingerprint mfr: "0312", prod: "FF00", model: "FF0D", deviceJoinName: "Minoston Dimmer Switch" //MP21ZD
        fingerprint mfr: "0312", prod: "FF07", model: "FF03", deviceJoinName: "Minoston Dimmer Switch" //MP22ZD
        fingerprint mfr: "0312", prod: "AC01", model: "4002", deviceJoinName: "Minoston Dimmer Switch" //N4002
    }

    preferences {
        configParams.each {
            if (it.name) {
                if (it.range) {
                    input "configParam${it.num}", "number", title: "${it.name}:", required: false, defaultValue: "${it.value}", range: it.range
                } else {
                    input "configParam${it.num}", "enum", title: "${it.name}:", required: false, defaultValue: "${it.value}", options: it.options
                }
            }
        }
    }
}

def installed() {
    logDebug "installed()..."
    sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
    state.refreshConfig = true
}

private static def getCheckInterval() {
    // These are battery-powered devices, and it's not very critical
    // to know whether they're online or not – 12 hrs
    return (60 * 60 * 3) + (5 * 60)
}

def updated() {
    if (!isDuplicateCommand(state.lastUpdated, 5000)) {
        state.lastUpdated = new Date().time

        logDebug "updated()..."
        if (device.latestValue("checkInterval") != checkInterval) {
            sendEvent(name: "checkInterval", value: checkInterval, displayed: false)
        }

        runIn(5, executeConfigureCmds, [overwrite: true])
    }

    return []
}

def configure() {
    logDebug "configure()..."

    if (state.resyncAll == null) {
        state.resyncAll = true
        runIn(8, executeConfigureCmds, [overwrite: true])
    } else {
        if (!pendingChanges) {
            state.resyncAll = true
        }
        executeConfigureCmds()
    }
    return []
}

def executeConfigureCmds() {
    runIn(6, refreshSyncStatus)

    def cmds = []

    if (!device.currentValue("switch")) {
        cmds << switchMultilevelGetCmd()
    }

    if (state.resyncAll || !device.currentValue("firmwareVersion")) {
        cmds << secureCmd(zwave.versionV1.versionGet())
    }

    configParams.each { param ->
        def storedVal = getParamStoredValue(param.num)
        if (state.resyncAll || ("${storedVal}" != "${param.value}")) {
            cmds << secureCmd(zwave.configurationV1.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: param.value))
            cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: param.num))
        }
    }

    state.resyncAll = false
    if (cmds) {
        sendCommands(cmds)
    }
    return []
}

void sendCommands(cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 500), hubitat.device.Protocol.ZWAVE))
}

def parse(String description) {
    def result = []
    try {
        def cmd = zwave.parse(description, commandClassVersions)
        if (cmd) {
            result += zwaveEvent(cmd)
        } else {
            logDebug "Unable to parse description: $description"
        }

        updateLastCheckIn()
    } catch (e) {
        log.error "$e"
    }
    return result
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def encapCmd = cmd.encapsulatedCommand(commandClassVersions)

    def result = []
    if (encapCmd) {
        result += zwaveEvent(encapCmd)
    } else {
        log.warn "Unable to extract encapsulated cmd from $cmd"
    }
    return result
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    logTrace "ConfigurationReport ${cmd}"

    sendEvent(name:  "syncStatus", value:  "Syncing...", displayed:  false)
    runIn(4, refreshSyncStatus)

    def param = configParams.find { it.num == cmd.parameterNumber }
    if (param) {
        def val = cmd.scaledConfigurationValue
        logDebug "${param.name}(#${param.num}) = ${val}"
        state["configParam${param.num}"] = val
    } else {
        logDebug "Parameter #${cmd.parameterNumber} = ${cmd.configurationValue}"
    }
    return []
}

def refreshSyncStatus() {
    def changes = pendingChanges
    sendEvent(name: "syncStatus", value:  (changes ?  "${changes} Pending Changes" : "Synced"), displayed:  false)
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug "Ignored Command: $cmd"
    return []
}

private secureCmd(cmd) {
    try {
        if (getDataValue("zwaveSecurePairingComplete") == "true") {
            return zwaveSecureEncap(cmd.format())
        } else {
            return cmd.format()
        }
    } catch (ex) {
        log.error("caught exception", ex)
    }
}

private static getCommandClassVersions() {
    [
        0x20: 1,	// Basic                     //BasicReport
        0x26: 3,	// Switch Multilevel         //SwitchMultilevelReport
        0x55: 1,	// Transport Service
        0x59: 1,	// AssociationGrpInfo        //AssociationGroupInfoReport       //DTH unimplemented interface
        0x5A: 1,	// DeviceResetLocally        //DeviceResetLocallyNotification   //DTH unimplemented interface
        0x71: 3,	// Notification              //NotificationReport               //DTH unimplemented interface
        0x6C: 1,	// Supervision               //SupervisionGet                   //DTH unimplemented interface
        0x70: 1,	// Configuration             //ConfigurationReport
        0x7A: 2,	// FirmwareUpdateMd          //FirmwareMdReport                 //DTH unimplemented interface
        0x72: 2,	// ManufacturerSpecific      //ManufacturerSpecificReport       //DTH unimplemented interface
        0x73: 1,	// Powerlevel
        0x85: 2,	// Association               //AssociationReport                //DTH unimplemented interface
        0x86: 1,	// Version (2)               //VersionReport
        0x8E: 2,	// Multi Channel Association //MultiChannelAssociationReport    //DTH unimplemented interface
        0x9F: 1		// Security S2               //SecurityMessageEncapsulation
    ]
}

private getPendingChanges() {
    return configParams.count { "${it.value}" != "${getParamStoredValue(it.num)}" }
}

private getParamStoredValue(paramNum) {
    return safeToInt(state["configParam${paramNum}"] , null)
}

// Configuration Parameters
private getConfigParams() {
    [
        ledModeParam,
        autoOffIntervalParam,
        autoOnIntervalParam,
        nightLightParam,
        powerFailureRecoveryParam,
        pushDimmingDurationParam,
        holdDimmingDurationParam,
        minimumBrightnessParam,
        maximumBrightnessParam
    ]
}

private getLedModeParam() {
    return getParam(2, "LED Indicator Mode", 1, 0, ledModeOptions)
}

private getAutoOffIntervalParam() {
    return getParam(4, "Auto Turn-Off Timer(0, Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getAutoOnIntervalParam() {
    return getParam(6, "Auto Turn-On Timer(0, Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getNightLightParam() {
    return getParam(7, "Night Light Settings(1 - 10:10% - 100%)", 1, 2, null, "1..10")
}

private getPowerFailureRecoveryParam() {
    return getParam(8, "Power Failure Recovery", 1, 2, powerFailureRecoveryOptions)
}

private getPushDimmingDurationParam() {
    return getParam(9, "Push Dimming Duration(0, Disabled; 1 - 10 Seconds)", 1, 2, null, "0..10")
}

private getHoldDimmingDurationParam() {
    return getParam(10, "Hold Dimming Duration(1 - 10 Seconds)", 1, 4, null, "1..10")
}

private getMinimumBrightnessParam() {
    return getParam(11, "Minimum Brightness(0, Disabled; 1 - 99:1% - 99%)", 1, 10, null,"0..99")
}

private getMaximumBrightnessParam() {
    return getParam(12, "Maximum Brightness(0, Disabled; 1 - 99:1% - 99%)", 1, 99, null,"0..99")
}

private getParam(num, name, size, defaultVal, options=null, range=null) {
    def val = safeToInt((settings ? settings["configParam${num}"] : null), defaultVal)

    def map = [num: num, name: name, size: size, value: val]
    if (options) {
        map.valueName = options?.find { k, v -> "${k}" == "${val}" }?.value
        map.options = setDefaultOption(options, defaultVal)
    }
    if (range) {
        map.range = range
    }

    return map
}

private static setDefaultOption(options, defaultVal) {
    return options?.collect{ k, v ->
        if ("${k}" == "${defaultVal}") {
            v = "${v} [DEFAULT]"
        }
        ["$k": "$v"]
    }
}

private static getLedModeOptions() {
    return [
        "0":"Off When On",
        "1":"On When On",
        "2":"Always Off",
        "3":"Always On"
    ]
}

private static getPowerFailureRecoveryOptions() {
    return [
        "0":"Turn Off",
        "1":"Turn On",
        "2":"Restore Last State"
    ]
}

private static validateRange(val, defaultVal, lowVal, highVal) {
    val = safeToInt(val, defaultVal)
    if (val > highVal) {
        return highVal
    } else if (val < lowVal) {
        return lowVal
    } else {
        return val
    }
}

private static safeToInt(val, defaultVal=0) {
    return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

private convertToLocalTimeString(dt) {
    def timeZoneId = location?.timeZone?.ID
    if (timeZoneId) {
        return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
    } else {
        return "$dt"
    }
}

private static isDuplicateCommand(lastExecuted, allowedMil) {
    !lastExecuted ? false : (lastExecuted + allowedMil > new Date().time)
}

private logDebug(msg) {
    log.debug "$msg"
}

private logTrace(msg) {
    log.trace "$msg"
}

def on() {
    logDebug "on()..."

    return [ basicSetCmd(0xFF) ]
}

def off() {
    logDebug "off()..."

    return [ basicSetCmd(0x00) ]
}

def setLevel(level) {
    logDebug "setLevel($level)..."
    return setLevel(level, 1)
}

def setLevel(level, duration) {
    logDebug "setLevel($level, $duration)..."
    if (duration > 30) {
        duration = 30
    }
    return [ switchMultilevelSetCmd(level, duration) ]
}

private basicSetCmd(val) {
    return secureCmd(zwave.basicV1.basicSet(value: val))
}

private switchMultilevelSetCmd(level, duration) {
    def levelVal = validateRange(level, 99, 0, 99)

    def durationVal = validateRange(duration, 1, 0, 100)

    return secureCmd(zwave.switchMultilevelV3.switchMultilevelSet(dimmingDuration: durationVal, value: levelVal))
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
    logTrace "VersionReport: ${cmd}"

    def subVersion = String.format("%02d", cmd.applicationSubVersion)
    def fullVersion = "${cmd.applicationVersion}.${subVersion}"

    sendEvent(name: "firmwareVersion",  value:fullVersion, displayed: true, type:  null)
    return []
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    logTrace "${cmd}"
    sendSwitchEvents(cmd.value, "physical")
    return []
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
    logTrace "${cmd}"
    sendSwitchEvents(cmd.value, "digital")
    return []
}

private sendSwitchEvents(rawVal, type) {
    def switchVal = rawVal ? "on" : "off"

    sendEvent(name: "switch",  value:switchVal, displayed: true, type: type)

    if (rawVal) {
        sendEvent(name: "level",  value:rawVal, displayed: true, type: type, unit:"%")
    }
}

private updateLastCheckIn() {
    if (!isDuplicateCommand(state.lastCheckInTime, 60000)) {
        state.lastCheckInTime = new Date().time

        def evt = [name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false]

        sendEvent(evt)

        if (childDevices) {
            childDevices*.sendEvent(evt)
        }
    }
}