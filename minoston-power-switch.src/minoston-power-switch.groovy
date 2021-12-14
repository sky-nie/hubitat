/**
 *  Minoston Power Switch v1.0.0(HUBITAT)
 *
 *  (Models: MP21ZP & MP22ZP)
 *
 *  Author:
 *   winnie (sky-nie)
 *
 *  Changelog:
 *
 *    1.0.0 (07/28/2021)
 *      - Initial Release
 *
 * Reference:
 *    https://raw.githubusercontent.com/pakmanwg/smartthings-peanut-plug/master/devicetypes/pakmanwg/peanut-plug.src/peanut-plug.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (
            name: "Minoston Power Switch",
            namespace: "sky-nie",
            author: "winnie",
            importUrl: "https://raw.githubusercontent.com/sky-nie/hubitat/main/minoston-power-switch.src/minoston-power-switch.groovy"
	) {
		capability "Actuator"
		capability "Sensor"
		capability "Switch"
		capability "Outlet"
		capability "Power Meter"
		capability "Energy Meter"
		capability "Voltage Measurement"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		attribute "energyDays",  "number"
		attribute "energyStatus", "string"
		capability "CurrentMeter"
		attribute "firmwareVersion", "number"

		attribute "lastCheckin", "string"
		attribute "history", "string"
		attribute "energyTime", "number"
		attribute "energyCost", "string"
		attribute "energyDuration", "string"

		["power", "voltage", "amperage"].each {
			attribute "${it}Low", "number"
			attribute "${it}High", "number"
		}

		command "reset"

		fingerprint mfr:"0312", prod:"FF00", model:"FF0E", deviceJoinName: "Minoston Outlet Meter"//Mini Smart Plug Meter, MP21ZP
		fingerprint mfr:"0312", prod:"FF00", model:"FF0F", deviceJoinName: "Minoston Outlet Meter"//Mini Smart Plug Meter, MP22ZP
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

		input "energyPrice", "decimal",
			title: "\$/kWh Cost:",
			defaultValue: energyPriceSetting,
			required: false,
			displayDuringSetup: true

		input "inactivePower", "decimal",
			title: "Report inactive when power is less than or equal to:",
			defaultValue: inactivePowerSetting,
			required: false,
			displayDuringSetup: true

		["Power", "Energy", "Voltage", "Amperage"].each {
			getBoolInput("display${it}", "Display ${it} Activity", true)
		}

		getBoolInput("debugOutput", "Enable Debug Logging", true)
	}
}

private getBoolInput(name, title, defaultVal) {
	input "${name}", "bool", 
		title: "${title}?", 
		defaultValue: defaultVal, 
		required: false
}

// Meters
private getMeterEnergy() {
	return getMeterMap("energy", 0, "kWh", null, settings?.displayEnergy != false)
}

private getMeterPower() {
	return getMeterMap("power", 2, "W", 2000, settings?.displayPower != false)
}

private getMeterVoltage() {
	return getMeterMap("voltage", 4, "V", 150, settings?.displayVoltage != false)
}

private getMeterAmperage() {
	return getMeterMap("amperage", 5, "A", 18, settings?.displayCurrent != false)
}

private static getMeterMap(name, scale, unit, limit, displayed) {
	return [name:name, scale:scale, unit:unit, limit: limit, displayed:displayed]
}

def installed() {
	logDebug "installed()..."
	return reset()
}

def updated() {
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {
		state.lastUpdated = new Date().time

		logDebug "updated()..."

		if (!state.dthVer23Config) {
			state.dthVer23Config = true
			sendEvent(name:"amperage", value: 0, unit: "A", displayed: true, isStateChange: true)
			sendEvent(name:"energyStatus", value: "tracking", displayed: true, isStateChange: true)
			sendEvent(name: "energyDays", value: calculateEnergyDays(), displayed: true, isStateChange: true)
			sendEvent(name: "firmwareVersion", value: roundTwoPlaces(device.currentValue("firmwareVersion")), displayed: true, isStateChange: true)
		}

		def cmds = configure()
		return cmds ? response(cmds) : []
	}
}

def configure() {
	logDebug "configure()..."
	def result = []

	def minReportingInterval = minimumReportingInterval

	if (state.minReportingInterval != minReportingInterval) {
		state.minReportingInterval = minReportingInterval

		// Set the Health Check interval so that it can be skipped twice plus 5 minutes.
		def checkInterval = ((minReportingInterval * 2) + (5 * 60))

		def eventMap = createEventMap("checkInterval", checkInterval, false)
		eventMap.data = [protocol: "zwave", hubHardwareId: device.hub.hardwareID]

		sendEvent(eventMap)
	}

	def cmds = []

	if (!device.currentValue("firmwareVersion")) {
		cmds << versionGetCmd()
	}

	configParams.each { param ->
		if (getParamIntVal(param) != getParamStoredIntVal(param)) {
			def newVal = getParamIntVal(param)
			logDebug "${param.name}(#${param.num}): changing ${getParamStoredIntVal(param)} to ${newVal}"
			cmds << secureCmd(zwave.configurationV2.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: newVal))
			cmds << secureCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
		}
	}
	result += cmds ? delayBetween(cmds, 1000) : []

	if (!device.currentValue("energyStatus")) {
		setEnergyStatusTracking()
	}

	if (!getAttrVal("energyTime")) {
		result << "delay 1000"
		result += reset()
	} else if (!state.configured) {
		result << "delay 1000"
		result += refresh()
	}
	return result
}

private getMinimumReportingInterval() {
	def minVal = (60 * 60 * 24 * 7)
	[powerReportIntervalParam, energyReportIntervalParam, voltageReportIntervalParam, electricityReportIntervalParam].each {
		def val = convertOptionSettingToInt(it.options, it.val)
		if (val && val < minVal) {
			minVal = val
		}
	}
	return minVal
}

def ping() {
	logDebug "ping()..."
	return [switchBinaryGetCmd()]
}

def on() {
	logDebug "on()..."
	return delayBetween([
		switchBinarySetCmd(0xFF),
		switchBinaryGetCmd()
	], 500)
}

def off() {
	logDebug "off()..."
	return delayBetween([
		switchBinarySetCmd(0x00),
		switchBinaryGetCmd()
	], 500)
}

def refresh() {
	logDebug "refresh()..."
	return delayBetween([
		switchBinaryGetCmd(),
		meterGetCmd(meterEnergy),
		meterGetCmd(meterPower),
		meterGetCmd(meterVoltage),
		meterGetCmd(meterAmperage),
		versionGetCmd()
	], 1000)
}

def setEnergyStatus(value) {
	logDebug "setEnergyStatus(${value})..."
	sendEvent(name: "energyStatus", value: "reset")
	runIn(2, setEnergyStatusTracking)
	return reset()
}

def setEnergyStatusTracking() {
	sendEvent(name: "energyStatus", value: "tracking", displayed: false)
}

def reset() {
	logDebug "reset()..."

	["power", "voltage", "amperage"].each {
		sendEvent(createEventMap("${it}Low", getAttrVal(it), false))
		sendEvent(createEventMap("${it}High", getAttrVal(it), false))
	}
	sendEvent(createEventMap("energyTime", new Date().time, false))
	sendEvent(createEventMap("energyDays", 0, false))

	def result = [
		secureCmd(zwave.meterV3.meterReset()),
		"delay 1000"
	]
	result += refresh()
	return result
}

private meterGetCmd(meter) {
	return secureCmd(zwave.meterV3.meterGet(scale: meter.scale))
}

private versionGetCmd() {
	return secureCmd(zwave.versionV1.versionGet())
}

private switchBinaryGetCmd() {
	return secureCmd(zwave.switchBinaryV1.switchBinaryGet())
}

private switchBinarySetCmd(val) {
	return secureCmd(zwave.switchBinaryV1.switchBinarySet(switchValue: val))
}

private secureCmd(cmd) {
	try {
		if (getDataValue("zwaveSecurePairingComplete") == "true") {
			return zwaveSecureEncap(cmd.format())
		} else {
			return cmd.format()
		}
	} catch (ex) {
		throw new RuntimeException(ex)
	}
}

def parse(String description) {
	def result = []
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)
	} else {
		log.warn "Unable to parse: $description"
	}

	if (!isDuplicateCommand(state.lastCheckinTime, 60000)) {
		state.lastCheckinTime = new Date().time
		sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
	}
	return result
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)

	def result = []
	if (encapsulatedCmd) {
		result += zwaveEvent(encapsulatedCmd)
	} else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

private static getCommandClassVersions() {
	[
		0x20: 1,	// Basic                       //BasicReport
		0x25: 1,	// Switch Binary               //SwitchBinaryReport
		0x27: 1,	// All Switch
		0x2B: 1,	// Scene Activation
		0x2C: 1,	// Scene Actuator Configuration//SceneActivationSet             //DTH unimplemented interface
		0x32: 3,	// Meter v4                    //MeterReport
		0x55: 1,	// Transport Service
		0x59: 1,	// AssociationGrpInfo          //AssociationGroupInfoReport     //DTH unimplemented interface
		0x5A: 1,	// DeviceResetLocally          //DeviceResetLocallyNotification //DTH unimplemented interface
		0x5E: 2,	// ZwaveplusInfo
		0x71: 3,	// NOTIFICATION_V8             //NotificationReport             //DTH unimplemented interface
		0x70: 2,	// Configuration               //ConfigurationReport
		0x72: 2,	// ManufacturerSpecific        //ManufacturerSpecificReport     //DTH unimplemented interface
		0x73: 1,	// Powerlevel
		0x7A: 2,	// Firmware Update Md (3)      //FirmwareMdReport               //DTH unimplemented interface
		0x85: 2,	// Association                 //AssociationReport              //DTH unimplemented interface
		0x86: 1,	// Version (2)                 //VersionReport
		0x8E: 2,	// Multi Channel Association   //MultiChannelAssociationReport  //DTH unimplemented interface
		0x98: 1		// Security 0                  //SecurityMessageEncapsulation
	]
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
	state.configured = true
	def configParam = configParams.find { param ->
		param.num == cmd.parameterNumber
	}

	if (configParam) {
		state["configVal${cmd.parameterNumber}"] = cmd.scaledConfigurationValue
	}
	return []
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"
	def subVersion = String.format("%02d", cmd.applicationSubVersion)
	def fullVersion = "${cmd.applicationVersion}.${subVersion}".toBigDecimal()
	if (fullVersion != device.currentValue("firmwareVersion")) {
		sendEvent(name:"firmwareVersion", value: fullVersion)
	}
	return []
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	logTrace "SwitchBinaryReport: ${cmd}"
	def result = []
	result << createSwitchEvent(cmd.value, "digital")
	return result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
	logTrace "BasicReport: ${cmd}"
	def result = []
	result << createSwitchEvent(cmd.value, "physical")
	return result
}

private createSwitchEvent(value, type) {
	def eventVal = (value == 0xFF) ? "on" : "off"
	def map = createEventMap("switch", eventVal, null, "Switch is ${eventVal}")
	map.type = type
	return createEvent(map)
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd) {
	logTrace "MeterReport: $cmd"
	def result = []
	def val = roundTwoPlaces(cmd.scaledMeterValue)

	def meter
	switch (cmd.scale) {
		case meterEnergy.scale:
			meter = meterEnergy
			break
		case meterPower.scale:
			def deviceActive = (device.currentValue("acceleration") == "active")
			if (val > inactivePowerSetting &&  !deviceActive) {
				sendEvent(name:"acceleration", value:"active", displayed:false)
			} else if (val <= inactivePowerSetting && deviceActive){
				sendEvent(name:"acceleration", value:"inactive", displayed:false)
			}
			meter = meterPower
			break
		case meterVoltage.scale:
			meter = meterVoltage
			break
		case meterAmperage.scale:
			meter = meterAmperage
			break
		default:
			logDebug "Unknown Meter Scale: $cmd"
	}

	if (meter?.limit && val > meter.limit) {
		log.warn "Ignored ${meter.name} value ${val}${meter.unit} because the highest possible value is ${meter.limit}${meter.unit}."
	} else if (meter?.name == meterEnergy.name) {
		if (getAttrVal("${meterEnergy.name}") != val) {
			sendEvent(createEventMap(meterEnergy.name, val, meterEnergy.displayed, null, meterEnergy.unit))

			def cost = "\$${roundTwoPlaces(val * energyPriceSetting)}"
			if (getAttrVal("energyCost") != cost) {
				sendEvent(createEventMap("energyCost", cost, false))
			}
		}

		sendEvent(createEventMap("energyDays", calculateEnergyDays(), false))
		sendEvent(createEventMap("energyDuration", calculateEnergyDuration(), false))
	} else if (meter?.name && getAttrVal("${meter.name}") != val) {
		result << createEvent(createEventMap(meter.name, val, meter.displayed, null, meter.unit))
		def highLowNames = []
		def highName = "${meter.name}High"
		def lowName = "${meter.name}Low"
		if (!getAttrVal(highName) || val > getAttrVal(highName)) {
			highLowNames << highName
		}
		if (!getAttrVal(lowName) || meter.value < getAttrVal(lowName)) {
			highLowNames << lowName
		}

		highLowNames.each {
			result << createEvent(createEventMap("$it", val, false, null, meter.unit))
		}
	}
	return result
}

private calculateEnergyDays() {
	def durationMinutes = energyDurationMinutes

	if (durationMinutes < 15) {
		return 0
	} else {
		return roundTwoPlaces(durationMinutes / (60 * 24))
	}
}

private calculateEnergyDuration() {
	def energyTimeMS = getAttrVal("energyTime")
	if (!energyTimeMS) {
		return "Unknown"
	} else {
		def duration = roundTwoPlaces(energyDurationMinutes)

		if (duration >= (24 * 60)) {
			return getFormattedDuration(duration, (24 * 60), "Day")
		} else if (duration >= 60) {
			return getFormattedDuration(duration, 60, "Hour")
		} else {
			return getFormattedDuration(duration, 0, "Minute")
		}
	}
}

private getEnergyDurationMinutes() {
	return ((new Date().time - (getAttrVal("energyTime") ?: 0)) / 60000)
}

private static getFormattedDuration(duration, divisor, name) {
	if (divisor) {
		duration = roundTwoPlaces(duration / divisor)
	}
	return "${duration} ${name}${duration == 1 ? '' : 's'}"
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	logDebug "Unhandled zwaveEvent: $cmd"
	return []
}

// Configuration Parameters
private getConfigParams() {
    return [
		ledModeParam,
		autoOffIntervalParam,
		autoOnIntervalParam,
		powerFailureRecoveryParam,
		powerValueChangeParam,
		powerReportIntervalParam,
		currentReportParam,
		electricityReportParam
    ]
}

private getLedModeParam() {
	return getParam(1, "LED Indicator Mode", 1, 0, ledModeOptions)
}

private getAutoOffIntervalParam() {
	return getParam(2, "Auto Turn-Off Timer(0,Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getAutoOnIntervalParam() {
	return getParam(3, "Auto Turn-On Timer(0,Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getPowerFailureRecoveryParam() {
	return getParam(4, "Power Failure Recovery", 1, 0, powerFailureRecoveryOptions)
}

private getPowerValueChangeParam() {
	return getParam(5, "Power Report Value Change(0 - 5:0W - 5w)", 1, 1, null, "0..5")
}

private getPowerReportIntervalParam() {
	return getParam(6, "Power Reporting Interval(1 - 65535:1 - 65535 minutes)", 4, 1, null, "1..65535")
}

private getCurrentReportParam() {
	return getParam(7, "Current Report Value Change(1 - 10:0.1A - 1A)", 1, 1, null, "1..10")
}

private getElectricityReportParam() {
	return getParam(8, "Electricity Report Value Change(1 - 100:0.01KWH - 1KWH)", 1, 1, null, "1..100")
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
	return options?.collect { k, v ->
		if ("${k}" == "${defaultVal}") {
			v = "${v} [DEFAULT]"
		}
		["$k": "$v"]
	}
}

private static getLedModeOptions() {
	return [
			"0":"On When On",
			"1":"Off When On",
			"2":"Always Off",
			"3":"Always On"
	]
}

private static getPowerFailureRecoveryOptions() {
	return [
			"0":"Remember last status",
			"1":"Turn Off",
			"2":"Turn On"
	]
}

// Settings
private getEnergyPriceSetting() {
	return safeToDec(settings?.energyPrice, 0.12)
}

private getInactivePowerSetting() {
	return safeToDec(settings?.inactivePower, 0)
}

private getDebugOutputSetting() {
	return settings?.debugOutput != false
}

private createEventMap(name, value, displayed=null, desc=null, unit=null) {
	desc = desc ?: "${name} is ${value}"

	def eventMap = [
		name: name,
		value: value,
		displayed: (displayed == null ? ("${getAttrVal(name)}" != "${value}") : displayed)
	]

	if (unit) {
		eventMap.unit = unit
		desc = "${desc} ${unit}"
	}

	if (desc && eventMap.displayed) {
		logDebug desc
		eventMap.descriptionText = "${device.displayName} - ${desc}"
	} else {
		logTrace "Creating Event: ${eventMap}"
	}
	return eventMap
}

private getAttrVal(attrName) {
	try {
		return device?.currentValue("${attrName}")
	} catch (ex) {
		logTrace "$ex"
		return null
	}
}

private static convertOptionSettingToInt(options, settingVal) {
	return safeToInt(options?.find { name, val -> "${settingVal}" == name }?.value, 0)
}

private static getParamIntVal(param) {
	return param.options ? convertOptionSettingToInt(param.options, param.val) : param.val
}

private getParamStoredIntVal(param) {
	return safeToInt(state["configVal${param.num}"] , null)
}

private static safeToInt(val, defaultVal=0) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

private static safeToDec(val, defaultVal=0) {
	return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private static roundTwoPlaces(val) {
	return Math.round(safeToDec(val) * 100) / 100
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
	if (debugOutputSetting) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	log.trace "$msg"
}