/**
 *  Minoston Power Switch v1.1.0(HUBITAT)
 *
 *  (Models: MP21ZP & MP22ZP)
 *
 *  Author:
 *   winnie (sky-nie)
 *
 *  Changelog:
 *
 *    1.1.0 (01/18/2022)
 *      - Fixed a bunch of error and clean up code.
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
		capability "CurrentMeter"

		attribute "lastCheckin", "string"
		attribute "history", "string"
		attribute "energyTime", "number"
		attribute "energyCost", "string"
		attribute "energyDays",  "number"
		attribute "energyDuration", "string"

		["power", "voltage", "amperage"].each {
			attribute "${it}Low", "number"
			attribute "${it}High", "number"
		}

		command "reset"

		fingerprint mfr:"0312", prod:"FF00", model:"FF0E", deviceJoinName: "Minoston Outlet Meter"//Mini Smart Plug Meter, MP21ZP
		fingerprint mfr:"0312", prod:"FF00", model:"FF0F", deviceJoinName: "Minoston Outlet Meter"//Mini Smart Plug Meter, MP22ZP
		fingerprint mfr:"0312", prod:"FF00", model:"FF11", deviceJoinName: "Minoston Outlet Meter"//Mini Power Meter Plug, ZW38M
		fingerprint mfr:"0312", prod:"AC01", model:"4003", deviceJoinName: "New One Outlet Meter" //Mini Power Meter Plug, N4003
	}

	preferences {
		configParams.each {
			if (it.name) {
				if (it.range) {
					input "configParam${it.num}", "number", title: "${it.name}:", required: false, defaultValue: it.value, range: it.range
				} else {
					input "configParam${it.num}", "enum", title: "${it.name}:", required: false, defaultValue: it.value, options: it.options
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
	return getMeterMap("energy", 0, "kWh", null, settings?.displayEnergy ?: true)
}

private getMeterPower() {
	return getMeterMap("power", 2, "W", 2000, settings?.displayPower ?: true)
}

private getMeterVoltage() {
	return getMeterMap("voltage", 4, "V", 150, settings?.displayVoltage ?: true)
}

private getMeterAmperage() {
	return getMeterMap("amperage", 5, "A", 18, settings?.displayCurrent ?: true)
}

private static getMeterMap(name, scale, unit, limit, displayed) {
	return [name:name, scale:scale, unit:unit, limit: limit, displayed:displayed]
}

def installed() {
	logDebug "installed()..."
	traceZwaveOutput(delayBetweenBatch(doReset()))
}

def updated() {
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {
		state.lastUpdated = new Date().time

		logDebug "updated()..."

		traceZwaveOutput(delayBetweenBatch(doConfigure()))
	}
}

def configure() {
	logDebug "configure()..."
	traceZwaveOutput(delayBetweenBatch(doConfigure()))
}

private doConfigure() {
	def cmds = []

	configParams.each { param ->
        def newVal = safeToInt(param.value)
		def storeVal = state."configVal${param.num}"
		if (newVal != storeVal) {
			logDebug "${param.name}(#${param.num}): changing ${storeVal} to ${newVal}"
			cmds << secureCmd(zwave.configurationV4.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: newVal))
			cmds << secureCmd(zwave.configurationV4.configurationGet(parameterNumber: param.num))
		}
	}

	if (!getAttrVal("energyTime")) {
		cmds += doReset()
	} else if (!state.configured) {
		cmds += doRefresh()
	}
	return cmds;
}

def ping() {
	logDebug "ping()..."
	return traceZwaveOutput([switchBinaryGetCmd()])
}

def on() {
	logDebug "on()..."
	return traceZwaveOutput(delayBetween([
		switchBinarySetCmd(0xFF),
		switchBinaryGetCmd()
	], 500))
}

def off() {
	logDebug "off()..."
	return traceZwaveOutput(delayBetween([
		switchBinarySetCmd(0x00),
		switchBinaryGetCmd()
	], 500))
}

def refresh() {
	logDebug "refresh()..."
	traceZwaveOutput(delayBetweenBatch(doRefresh()))
}

private doRefresh() {
    cmds = [
		switchBinaryGetCmd(),
		meterGetCmd(meterEnergy),
		meterGetCmd(meterPower),
		meterGetCmd(meterVoltage),
		meterGetCmd(meterAmperage),
		versionGetCmd()
	]
	configParams.each { 
		param -> cmds << secureCmd(zwave.configurationV4.configurationGet(parameterNumber: param.num))
	}
	return cmds
}

def reset() {
	logDebug "reset()..."
	traceZwaveOutput(delayBetweenBatch(doReset()))
}

private doReset() {
	["power", "voltage", "amperage"].each {
		sendEvent(createEventMap("${it}Low", getAttrVal(it), false))
		sendEvent(createEventMap("${it}High", getAttrVal(it), false))
	}
	sendEvent(createEventMap("energyTime", new Date().time, false))
	sendEvent(createEventMap("energyDays", 0, false))

	return [secureCmd(zwave.meterV5.meterReset())] + doRefresh()
}

private meterGetCmd(meter) {
	return secureCmd(zwave.meterV5.meterGet(scale: meter.scale))
}

private versionGetCmd() {
	return secureCmd(zwave.versionV2.versionGet())
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
		0x25: 2,	// Switch Binary               //SwitchBinaryReport
		0x27: 1,	// All Switch
		0x32: 5,	// Meter V5                    //MeterReport
		0x55: 2,	// Transport Service
		0x59: 3,	// AssociationGrpInfo          //AssociationGroupInfoReport     //DTH unimplemented interface
		0x5A: 1,	// DeviceResetLocally          //DeviceResetLocallyNotification //DTH unimplemented interface
		0x5E: 2,	// ZwaveplusInfo
		0x6C: 1,	// Supervision
		0x70: 4,	// Configuration               //ConfigurationReport
		0x71: 8,	// Notification V8             //NotificationReport             //DTH unimplemented interface
		0x72: 2,	// ManufacturerSpecific        //ManufacturerSpecificReport     //DTH unimplemented interface
		0x73: 1,	// Powerlevel
		0x7A: 5,	// Firmware Update Md V5       //FirmwareMdReport               //DTH unimplemented interface
		0x85: 3,	// Association                 //AssociationReport              //DTH unimplemented interface
		0x86: 2,	// Version V2                  //VersionReport
		0x87: 3,	// Indicator V3
		0x8E: 4,	// Multi Channel Association   //MultiChannelAssociationReport  //DTH unimplemented interface
		0x98: 1,	// Security 0                  //SecurityMessageEncapsulation
		0x9F: 2 	// Security 2
	]
}

def zwaveEvent(hubitat.zwave.commands.configurationv4.ConfigurationReport cmd) {
	logTrace "ZWave ConfigurationReport: ${cmd}"
	state.configured = true
	def configParam = configParams.find { param ->
		param.num == cmd.parameterNumber
	}

	if (configParam) {
		state["configVal${cmd.parameterNumber}"] = cmd.scaledConfigurationValue
	}
	return []
}

def zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
	logTrace "ZWave VersionReport: ${cmd}"
	def fullVersion = "${cmd.firmware0Version}.${cmd.firmware0SubVersion}".toBigDecimal()
	if (fullVersion != getDataValue("firmwareVersion").toBigDecimal()) {
		updateDataValue("firmwareVersion", fullVersion)
	}
	return []
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	logTrace "ZWave SwitchBinaryReport: ${cmd}"
	def result = []
	result << createSwitchEvent(cmd.value, "digital")
	return result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
	logTrace "ZWave BasicReport: ${cmd}"
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

def zwaveEvent(hubitat.zwave.commands.meterv5.MeterReport cmd) {
	logTrace "ZWave MeterReport: $cmd"
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
		def highName = "${meter.name}High"
		if (!getAttrVal(highName) || val > getAttrVal(highName)) {
			result << createEvent(createEventMap(highName, val, false, null, meter.unit))
		}
		def lowName = "${meter.name}Low"
		if (!getAttrVal(lowName) || val < getAttrVal(lowName)) {
			result << createEvent(createEventMap(lowName, val, false, null, meter.unit))
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
			return getFormattedDuration(duration, 1, "Minute")
		}
	}
}

private getEnergyDurationMinutes() {
	return ((new Date().time - (getAttrVal("energyTime") ?: 0)) / 60000)
}

private static getFormattedDuration(duration, divisor, name) {
	duration = roundTwoPlaces(duration / divisor)
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
	return getParam(2, "Auto Turn-Off Timer(0[DEFAULT],Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getAutoOnIntervalParam() {
	return getParam(3, "Auto Turn-On Timer(0[DEFAULT],Disabled; 1 - 65535 minutes)", 4, 0, null, "0..65535")
}

private getPowerFailureRecoveryParam() {
	return getParam(4, "Restores state after power failure", 1, 0, powerFailureRecoveryOptions)
}

private getPowerValueChangeParam() {
	return getParam(5, "Power Wattage(W) Report Value Change (1 [DEFAULT]; 0 - 5:0W - 5w)", 1, 1, null, "0..5")
}

private getPowerReportIntervalParam() {
	return getParam(6, "Time Report Interval(1[DEFAULT] - 65535:1 - 65535 minutes)", 4, 1, null, "1..65535")
}

private getCurrentReportParam() {
	return getParam(7, "Current(A) Report Value Change(1[DEFAULT] - 10:0.1A - 1A)", 1, 1, null, "1..10")
}

private getElectricityReportParam() {
	return getParam(8, "Energy(KWH) Report Value Change(1[DEFAULT] - 100:0.01KWH - 1KWH)", 1, 1, null, "1..100")
}

private getParam(num, name, size, defaultVal, options=null, range=null) {
	def val = settings?."configParam${num}" ?: defaultVal;

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
    if (options != null  && options.containsKey(defaultVal)) {
        options[defaultVal] = options[defaultVal] + " [DEFAULT]"
    }
	return options;
}

private static getLedModeOptions() {
	return [
			0:"On When On",
			1:"Off When On",
			2:"Always Off",
			3:"Always On"
	]
}

private static getPowerFailureRecoveryOptions() {
	return [
			0:"Remember last status",
			1:"Turn Off",
			2:"Turn On"
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
		displayed: (displayed ?: ("${getAttrVal(name)}" != "${value}"))
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
		log.error "$ex"
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
	} else {
		return "$dt"
	}
}

private static isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time)
}

private traceZwaveOutput(cmds) {
	logTrace(cmds ? "Zwave sending: ${cmds}" : "Zwave no command to send")
	return cmds
}

private logDebug(msg) {
	if (debugOutputSetting) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	if (debugOutputSetting) {
		log.trace "$msg"
	}
}

private delayBetweenBatch(cmds, delay=500, batch=4, pause=3000) {
	def result = []
	cmds.eachWithIndex{ cmd, idx ->
		result << cmd
		result << "delay ${(idx+1) % batch == 0 ? pause : delay}"
	}
	if (result) result.pop() // before groovy 2.5.0
	return result
}
