/**
 *  Minoston Power Switch v1.0.0(HUBITAT)
 *
 *  (Models: MP21ZP)
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
 *    https://github.com/krlaframboise/SmartThings/blob/master/devicetypes/krlaframboise/zooz-power-switch.src/zooz-power-switch.groovy
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
		ocfDeviceType: "oic.d.smartplug",
		mnmn: "SmartThingsCommunity",
		vid: "1957bce8-d1a4-3d5e-b768-835935de293b"
	) {
		capability "Actuator"
		capability "Sensor"
		capability "Switch"
		capability "Power Meter"
		capability "Energy Meter"
		capability "Voltage Measurement"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		capability "booklocket57627.amperageMeasurement"

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
	}

	preferences {
		configParams?.each {
			getOptionsInput(it)
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

		getBoolInput("disableOnHubControl", "Disable 'On' Control from the Hub", false)

		getBoolInput("disableOffHubControl", "Disable 'Off' Control from the Hub", false)

		getBoolInput("debugOutput", "Enable Debug Logging", true)
	}
}

private getOptionsInput(param) {
	if (param.prefName) {
		input "${param.prefName}", "enum",
			title: "${param.name}:",
			defaultValue: "${param.val}",
			required: false,
			displayDuringSetup: true,
			options: param.options?.collect { name, val -> name }
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

	updateHealthCheckInterval()

	def cmds = []

	if (!device.currentValue("firmwareVersion")) {
		cmds << versionGetCmd()
	}

	configParams.each { param ->
		cmds += updateConfigVal(param)
	}
	result += cmds ? delayBetween(cmds, 1000) : []

	if (!device.currentValue("energyStatus")) {
		setEnergyStatusTracking()
	}

	if (!getAttrVal("energyTime")) {
		result << "delay 1000"
		result += reset()
	}
	else if (!state.configured) {
		result << "delay 1000"
		result += refresh()
	}
	return result
}

private updateConfigVal(param) {
	def cmds = []
	if (hasPendingChange(param)) {
		def newVal = getParamIntVal(param)
		logDebug "${param.name}(#${param.num}): changing ${getParamStoredIntVal(param)} to ${newVal}"
		cmds << configSetCmd(param, newVal)
		cmds << configGetCmd(param)
	}
	return cmds
}

private hasPendingChange(param) {
	if (param.num != manualControlParam.num || isFirmwareVersion2()) {
		return (getParamIntVal(param) != getParamStoredIntVal(param))
	}
	else {
		if (getParamIntVal(param) == 0) {
			logNotSupportedMessage("Manual Control option 'Disabled'", "")
		}
		return false
	}
}

void updateHealthCheckInterval() {
	def minReportingInterval = minimumReportingInterval

	if (state.minReportingInterval != minReportingInterval) {
		state.minReportingInterval = minReportingInterval

		// Set the Health Check interval so that it can be skipped twice plus 5 minutes.
		def checkInterval = ((minReportingInterval * 2) + (5 * 60))

		def eventMap = createEventMap("checkInterval", checkInterval, false)
		eventMap.data = [protocol: "zwave", hubHardwareId: device.hub.hardwareID]

		sendEvent(eventMap)
	}
}

def ping() {
	logDebug "ping()..."
	sendHubCommand(switchBinaryGetCmd(), 100)
	return []
}

def on() {
	if (!settings?.disableOnHubControl) {
		logDebug "on()..."
		return delayBetween([
			switchBinarySetCmd(0xFF),
			switchBinaryGetCmd()
		], 500)
	}
	else {
		logDisabledHubControlMessage("on")
	}
}

def off() {
	if (!settings?.disableOffHubControl) {
		logDebug "off()..."
		return delayBetween([
			switchBinarySetCmd(0x00),
			switchBinaryGetCmd()
		], 500)
	}
	else {
		logDisabledHubControlMessage("off")
	}
}

private logDisabledHubControlMessage(cmd) {
	log.warn "Ignored '${cmd}' command because the 'Disable ${cmd} Control from the Hub' setting is enabled."
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
		meterResetCmd(),
		"delay 1000"
	]
	result += refresh()
	return result
}

private meterGetCmd(meter) {
	return secureCmd(zwave.meterV3.meterGet(scale: meter.scale))
}

private meterResetCmd() {
	return secureCmd(zwave.meterV3.meterReset())
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

private configSetCmd(param, val) {
	return secureCmd(zwave.configurationV2.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: val))
}

private configGetCmd(param) {
	return secureCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
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

def parse(String description) {
	def result = []
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)
	}
	else {
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
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

private static getCommandClassVersions() {
	[
		0x20: 1,	// Basic
		0x25: 1,	// Switch Binary
		0x27: 1,	// All Switch
		0x2B: 1,	// Scene Activation
		0x2C: 1,	// Scene Actuator Configuration
		0x32: 3,	// Meter v4
		0x55: 1,	// Transport Service
		0x59: 1,	// AssociationGrpInfo
		0x5A: 1,	// DeviceResetLocally
		0x5E: 2,	// ZwaveplusInfo
		0x71: 3,	// NOTIFICATION_V8
		0x70: 2,	// Configuration
		0x72: 2,	// ManufacturerSpecific
		0x73: 1,	// Powerlevel
		0x7A: 2,	// Firmware Update Md (3)
		0x85: 2,	// Association
		0x86: 1,	// Version (2)
		0x8E: 2,	// Multi Channel Association
		0x98: 1		// Security
	]
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
	def val = cmd.scaledConfigurationValue

	state.configured = true

	def configParam = configParams.find { param ->
		param.num == cmd.parameterNumber
	}

	if (configParam) {
		def nameVal = val
		// Led config parameters have different values based on firmware.
		if (configParam.num == ledIndicatorParam.num && !isFirmwareVersion2() && val == 1) {
			nameVal = 2
		}

		def name = configParam.options?.find { it.value == nameVal}?.key
		logDebug "${configParam.name}(#${configParam.num}) = ${name != null ? name : val} (${val})"
		state["configVal${cmd.parameterNumber}"] = val

		// if (configParam.num == overloadProtectionParam.num) {
		// refreshHistory()
		// }

	}
	else {
		logDebug "Parameter ${cmd.parameterNumber} = ${val}"
	}
	return []
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"

	def subVersion = String.format("%02d", cmd.applicationSubVersion)
	def fullVersion = "${cmd.applicationVersion}.${subVersion}".toBigDecimal()

	logDebug "Firmware Version: ${fullVersion}"
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
			createAccelerationEvent(val)
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
	}
	else if (meter?.name == meterEnergy.name) {
		sendEnergyEvents(val)
	}
	else if (meter?.name && getAttrVal("${meter.name}") != val) {
		result << createEvent(createEventMap(meter.name, val, meter.displayed, null, meter.unit))

		result += createHighLowEvents(meter, val)

		// runIn(5, refreshHistory)
	}
	return result
}

private createAccelerationEvent(val) {
	def deviceActive = (device.currentValue("acceleration") == "active")
	if (val > inactivePowerSetting &&  !deviceActive) {
		sendEvent(name:"acceleration", value:"active", displayed:false)
	}
	else if (val <= inactivePowerSetting && deviceActive){
		sendEvent(name:"acceleration", value:"inactive", displayed:false)
	}
}

private createHighLowEvents(meter, val) {
	def result = []
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
	return result
}

private sendEnergyEvents(val) {
	if (getAttrVal("${meterEnergy.name}") != val) {
		sendEvent(createEventMap(meterEnergy.name, val, meterEnergy.displayed, null, meterEnergy.unit))

		def cost = "\$${roundTwoPlaces(val * energyPriceSetting)}"
		if (getAttrVal("energyCost") != cost) {
			sendEvent(createEventMap("energyCost", cost, false))
		}
	}

	sendEvent(createEventMap("energyDays", calculateEnergyDays(), false))
	sendEvent(createEventMap("energyDuration", calculateEnergyDuration(), false))
}

private calculateEnergyDays() {
	def durationMinutes = energyDurationMinutes

	if (durationMinutes < 15) {
		return 0
	}
	else {
		return roundTwoPlaces(durationMinutes / (60 * 24))
	}
}

private calculateEnergyDuration() {
	def energyTimeMS = getAttrVal("energyTime")
	if (!energyTimeMS) {
		return "Unknown"
	}
	else {
		def duration = roundTwoPlaces(energyDurationMinutes)

		if (duration >= (24 * 60)) {
			return getFormattedDuration(duration, (24 * 60), "Day")
		}
		else if (duration >= 60) {
			return getFormattedDuration(duration, 60, "Hour")
		}
		else {
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
		ledIndicatorParam,
		autoOffIntervalParam,
		autoOnIntervalParam,
		powerFailureRecoveryParam,
		powerValueChangeParam,
		powerReportIntervalParam,
		currentReportParam,
		electricityReportParam
    ]
}

private getLedIndicatorParam() {
	return createConfigParamMap(1, "LED Indicator Mode", 1,  ["On When On${defaultOptionSuffix}":0, "Off When On":1, "Always Off":2, "Always On":3], "LED Indicator Mode")
}

private getAutoOffIntervalParam() {
	return createConfigParamMap(2, "Auto Turn-Off Timer", 4,  ["Disabled${defaultOptionSuffix}":0, "1 Minute":1, "2 Minutes":2, "3 Minutes":3, "4 Minutes":4, "5 Minutes":5, "6 Minutes":6, "7 Minutes":7, "8 Minutes":8, "9 Minutes":9, "10 Minutes":10, "15 Minutes":15, "20 Minutes":20, "25 Minutes":25, "30 Minutes":30, "45 Minutes":45, "1 Hour":60, "2 Hours":120, "3 Hours":180, "4 Hours":240, "5 Hours":300, "6 Hours":360, "7 Hours":420, "8 Hours":480, "9 Hours":540, "10 Hours":600, "12 Hours":720, "18 Hours":1080, "1 Day":1440, "2 Days":2880, "3 Days":4320, "4 Days":5760, "5 Days":7200, "6 Days":8640, "1 Week":10080, "2 Weeks":20160, "3 Weeks":30240, "4 Weeks":40320, "5 Weeks":50400, "6 Weeks":60480], "Auto Turn-Off Timer")
}

private getAutoOnIntervalParam() {
	return createConfigParamMap(3, "Auto Turn-On Timer", 4, ["Disabled${defaultOptionSuffix}":0, "1 Minute":1, "2 Minutes":2, "3 Minutes":3, "4 Minutes":4, "5 Minutes":5, "6 Minutes":6, "7 Minutes":7, "8 Minutes":8, "9 Minutes":9, "10 Minutes":10, "15 Minutes":15, "20 Minutes":20, "25 Minutes":25, "30 Minutes":30, "45 Minutes":45, "1 Hour":60, "2 Hours":120, "3 Hours":180, "4 Hours":240, "5 Hours":300, "6 Hours":360, "7 Hours":420, "8 Hours":480, "9 Hours":540, "10 Hours":600, "12 Hours":720, "18 Hours":1080, "1 Day":1440, "2 Days":2880, "3 Days":4320, "4 Days":5760, "5 Days":7200, "6 Days":8640, "1 Week":10080, "2 Weeks":20160, "3 Weeks":30240, "4 Weeks":40320, "5 Weeks":50400, "6 Weeks":60480], "Auto Turn-On Timer")
}

private getPowerFailureRecoveryParam() {
	return createConfigParamMap(4, "Power Failure Recovery", 1, ["Remember last status${defaultOptionSuffix}":0, "Turn Off":1, "Turn On":2], "powerFailureRecovery")
}

private getPowerValueChangeParam() {
	return createConfigParamMap(5, "Power Report Value Change", 1, ["0W":0, "1W${defaultOptionSuffix}":1,"2W":2, "3W":3, "4W":4,"5W":5], "powerValueChange")
}

private getPowerReportIntervalParam() {
	return createConfigParamMap(6, "Power Reporting Interval", 4, ["1 Minute${defaultOptionSuffix}":1, "2 Minutes":2, "3 Minutes":3, "4 Minutes":4, "5 Minutes":5, "6 Minutes":6, "7 Minutes":7, "8 Minutes":8, "9 Minutes":9, "10 Minutes":10, "15 Minutes":15, "20 Minutes":20, "25 Minutes":25, "30 Minutes":30, "45 Minutes":45, "1 Hour":60, "2 Hours":120, "3 Hours":180, "4 Hours":240, "5 Hours":300, "6 Hours":360, "7 Hours":420, "8 Hours":480, "9 Hours":540, "10 Hours":600, "12 Hours":720, "18 Hours":1080, "1 Day":1440, "2 Days":2880, "3 Days":4320, "4 Days":5760, "5 Days":7200, "6 Days":8640, "1 Week":10080, "2 Weeks":20160, "3 Weeks":30240, "4 Weeks":40320, "5 Weeks":50400, "6 Weeks":60480], "Power Reporting Interval")
}

private getCurrentReportParam() {
	return createConfigParamMap(7, "Current Report Value Change", 1, ["0.1A${defaultOptionSuffix}":1, "0.2A":2, "0.3A":3, "0.4A":4, "0.5A":5, "0.6A":6, "0.7A":7, "0.8A":8, "0.9A":9, "1A":10], "Current Report Value Change")
}

private getElectricityReportParam() {
	return createConfigParamMap(8, "Electricity Report Value Change", 1, ["0.01KWH${defaultOptionSuffix}":1, "0.02KWH":2, "0.03KWH":3, "0.04KWH":4, "0.05KWH":5, "0.06KWH":6, "0.07KWH":7, "0.08KWH":8, "0.09KWH":9, "0.10KWH":10, "0.15KWH":15, "0.20KWH":20, "0.25KWH":25, "0.30KWH":30, "0.35KWH":35, "0.40KWH":40, "0.45KWH":45, "0.50KWH":50, "0.55KWH":55, "0.60KWH":60, "0.65KWH":75, "0.70KWH":70, "0.75KWH":75, "0.80KWH":80, "0.85KWH":85, "0.90KWH":90, "0.95KWH":95, "1KWH":100], "Electricity Report Value Change")
}

private getParamStoredIntVal(param) {
	return state["configVal${param.num}"]
}

private getParamIntVal(param) {
	def val = param.options ? convertOptionSettingToInt(param.options, param.val) : param.val

	if (param.num == ledIndicatorParam.num) {
		val = getLedSettingSupportedByFirmware(val)
	}
	return val
}

private getLedSettingSupportedByFirmware(val) {
	if (!isFirmwareVersion2()) {
		switch (val) {
			case 1:
				logNotSupportedMessage("LED Power Consumption option 'Show When On'", "so using 'Always Show' instead")
				val = 0
				break
			case 2:
				val = 1
				break
			case 3:
				logNotSupportedMessage("LED Power Consumption option 'Always Off'", "so using 'Always Show'")
				val = 0
				break
		}
	}
	return val
}

private logNotSupportedMessage(prefix, suffix) {
	def firmware = device.currentValue("firmwareVersion")
	log.warn "${prefix} is not supported by firmware ${firmware} ${suffix}"
}

private createConfigParamMap(num, name, size, options, prefName, val=null) {
	if (val == null) {
		val = (settings?."${prefName}" ?: findDefaultOptionName(options))
	}
	return [
		num: num, 
		name: name, 
		size: size, 
		options: options, 
		prefName: prefName,
		val: val
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

private static convertOptionSettingToInt(options, settingVal) {
	return safeToInt(options?.find { name, val -> "${settingVal}" == name }?.value, 0)
}

private static findDefaultOptionName(options) {
	def option = options?.find { name, val ->
		name?.contains("${defaultOptionSuffix}") 
	}
	return option?.key ?: ""
}

private static getDefaultOptionSuffix() {
	return "[Default]"
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
	}
	else {
		logTrace "Creating Event: ${eventMap}"
	}
	return eventMap
}

private getAttrVal(attrName) {
	try {
		return device?.currentValue("${attrName}")
	}
	catch (ex) {
		logTrace "$ex"
		return null
	}
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
	}
	else {
		return "$dt"
	}
}

private static isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}

private isFirmwareVersion2() {
	return safeToDec(device.currentValue("firmwareVersion")) >= 1.03
}

private logDebug(msg) {
	if (debugOutputSetting) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	log.trace "$msg"
}