/**
 *  Outdoor Smart Plug 2-Channel ZW97
 *  Author: 
 *  Date: 
 *
 *  Copyright 2020
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
 *  2019-11-20: Fixed Association Group management.
 *
 *  2018-05-02: Added support for Z-Wave Association Tool SmartApp. Associations require firmware 1.02+.
 *
 */

metadata {
    definition(
            name: "Outdoor Smart Plug 2-Channel ZW97",
            namespace: "Evalogik",
            author: "sky-nie",
            importUrl: "https://raw.githubusercontent.com/sky-nie/hubitat/main/evalogik/evalogik-outdoor-smart-plug.groovy"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Polling"
        capability "Refresh"
        capability "Health Check"
        capability "PushableButton"
        capability "Configuration"

        attribute "lastActivity", "String"
        attribute "lastEvent", "String"

        command "setAssociationGroup", [[name: "Group Number*",type:"NUMBER", description: "Provide the association group number to edit"],
                                        [name: "Z-Wave Node*", type:"STRING", description: "Enter the node number (in hex) associated with the node"],
                                        [name: "Action*", type:"ENUM", constraints: ["Add", "Remove"]],
                                        [name:"Multi-channel Endpoint", type:"NUMBER", description: "Currently not implemented"]]

        command "childOn"
        command "childOff"
        command "childRefresh"


        fingerprint mfr: "0312", prod: "C000", deviceId: "C007", deviceJoinName: "Outdoor Smart Plug 2-Channel ZW97", inClusters:"0x5E,0x6C,0x55,0x9F"  // ZW97
        fingerprint mfr: "0312", prod: "C000", deviceId: "C007", deviceJoinName: "Outdoor Smart Plug 2-Channel ZW97", inClusters:"0x86,0x25,0x85,0x8E,0x59,0x60,0x72,0x5A,0x73,0x70,0x7A"
    }

    simulator {}

    preferences {
        input "autoOff1", "number", title: "Auto Off Channel 1\n\nAutomatically turn switch off after this number of seconds\nRange: 0 to 32767", description: "Tap to set", required: false, range: "0..32767"
        input "autoOff2", "number", title: "Auto Off Channel 2\n\nAutomatically turn switch off after this number of seconds\nRange: 0 to 32767", description: "Tap to set", required: false, range: "0..32767"
        input "ledIndicator", "enum", title: "LED Indicator\n\nTurn LED indicator on when switch is:\n", description: "Tap to set", required: false, options:[["0": "On"], ["1": "Off"], ["2": "Disable"]], defaultValue: "0"
        input description: "Use the \"Z-Wave Association Tool\" SmartApp to set device associations. (Firmware 1.02+)\n\nGroup 2: Sends on/off commands to associated devices when switch is pressed (BASIC_SET).", title: "Associations", displayDuringSetup: false, type: "paragraph", element: "paragraph"
    }

    tiles {
        multiAttributeTile(name: "switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "off", label: '${name}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", nextState: "turningOn"
                attributeState "on", label: '${name}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00a0dc", nextState: "turningOff"
                attributeState "turningOff", label: '${name}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", nextState: "turningOn"
                attributeState "turningOn", label: '${name}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00a0dc", nextState: "turningOff"
            }
        }

        standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label: "", action: "refresh.refresh", icon: "st.secondary.refresh"
        }

        valueTile("lastActivity", "device.lastActivity", inactiveLabel: false, decoration: "flat", width: 4, height: 1) {
            state "default", label: 'Last Activity: ${currentValue}',icon: "st.Health & Wellness.health9"
        }

        valueTile("icon", "device.icon", inactiveLabel: false, decoration: "flat", width: 4, height: 1) {
            state "default", label: '', icon: "https://inovelli.com/wp-content/uploads/Device-Handler/Inovelli-Device-Handler-Logo.png"
        }
    }
}
def parse(String description) {
    def result = []
    def cmd = zwave.parse(description)
    if (cmd) {
        result += zwaveEvent(cmd)
        log.debug "Parsed ${cmd} to ${result.inspect()}"
    } else {
        log.debug "Non-parsed event: ${description}"
    }

    def now
    if(location.timeZone)
        now = new Date().format("yyyy MMM dd EEE h:mm:ss a", location.timeZone)
    else
        now = new Date().format("yyyy MMM dd EEE h:mm:ss a")
    sendEvent(name: "lastActivity", value: now, displayed:false)

    return result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, ep = null) {
    log.debug "BasicReport ${cmd} - ep ${ep}"
    if (ep) {
        def event
        childDevices.each {
            childDevice ->
                if (childDevice.deviceNetworkId == "$device.deviceNetworkId-ep$ep") {
                    childDevice.sendEvent(name: "switch", value: cmd.value ? "on" : "off")
                }
        }
        if (cmd.value) {
            event = [createEvent([name: "switch", value: "on"])]
        } else {
            def allOff = true
            childDevices.each {
                n ->
                    if (n.currentState("switch").value != "off") allOff = false
            }
            if (allOff) {
                event = [createEvent([name: "switch", value: "off"])]
            } else {
                event = [createEvent([name: "switch", value: "on"])]
            }
        }
        return event
    }
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, ep = null) {
    log.debug "SwitchBinaryReport ${cmd} - ep ${ep}"
    if (ep) {
        def event
        def childDevice = childDevices.find {
            it.deviceNetworkId == "$device.deviceNetworkId-ep$ep"
        }
        if (childDevice) childDevice.sendEvent(name: "switch", value: cmd.value ? "on" : "off")
        if (cmd.value) {
            event = [createEvent([name: "switch", value: "on"])]
        } else {
            def allOff = true
            childDevices.each {
                n->
                    if (n.deviceNetworkId != "$device.deviceNetworkId-ep$ep" && n.currentState("switch")?.value != "off") allOff = false
            }
            if (allOff) {
                event = [createEvent([name: "switch", value: "off"])]
            } else {
                event = [createEvent([name: "switch", value: "on"])]
            }
        }
        return event
    } else {
        def result = createEvent(name: "switch", value: cmd.value ? "on" : "off", type: "digital")
        //def cmds = []
        //cmds << encap(zwave.switchBinaryV1.switchBinaryGet(), 1)
        //cmds << encap(zwave.switchBinaryV1.switchBinaryGet(), 2)
        return [result] // returns the result of reponse()
    }
}

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
    log.debug "MultiChannelCmdEncap ${cmd}"
    def encapsulatedCommand = cmd.encapsulatedCommand([0x32: 3, 0x25: 1, 0x20: 1])
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
    }
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    log.debug "ManufacturerSpecificReport ${cmd}"
    def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
    log.debug "msr: $msr"
    updateDataValue("MSR", msr)
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    // This will capture any commands not handled by other instances of zwaveEvent
    // and is recommended for development so you can see every command the device sends
    log.debug "Unhandled Event: ${cmd}"
}

def on() {
    log.debug "on()"
    commands([
            //zwave.switchAllV1.switchAllOn(),
            encap(zwave.basicV1.basicSet(value: 0xFF), 1),
            encap(zwave.basicV1.basicSet(value: 0xFF), 2)
    ])
}

def off() {
    log.debug "off()"
    commands([
            //zwave.switchAllV1.switchAllOff(),
            encap(zwave.basicV1.basicSet(value: 0x00), 1),
            encap(zwave.basicV1.basicSet(value: 0x00), 2)
    ])
}

def childOn(String dni) {
    log.debug "childOn($dni)"
    def cmds = []
    cmds << new hubitat.device.HubAction(command(encap(zwave.basicV1.basicSet(value: 0xFF), channelNumber(dni))), hubitat.device.Protocol.ZWAVE)
    //cmds << new hubitat.device.HubAction(command(encap(zwave.switchBinaryV1.switchBinaryGet(), channelNumber(dni))), hubitat.device.Protocol.ZWAVE)
    cmds
}

def childOff(String dni) {
    log.debug "childOff($dni)"
    def cmds = []
    cmds << new hubitat.device.HubAction(command(encap(zwave.basicV1.basicSet(value: 0x00), channelNumber(dni))), hubitat.device.Protocol.ZWAVE)
    //cmds << new hubitat.device.HubAction(command(encap(zwave.switchBinaryV1.switchBinaryGet(), channelNumber(dni))), hubitat.device.Protocol.ZWAVE)
    cmds
}

def childRefresh(String dni) {
    log.debug "childRefresh($dni)"
    def cmds = []
    cmds << new hubitat.device.HubAction(command(encap(zwave.switchBinaryV1.switchBinaryGet(), channelNumber(dni))), hubitat.device.Protocol.ZWAVE)
    cmds
}

def poll() {
    log.debug "poll()"
    commands([
            encap(zwave.switchBinaryV1.switchBinaryGet(), 1),
            encap(zwave.switchBinaryV1.switchBinaryGet(), 2),
    ])
}

def refresh() {
    log.debug "refresh()"
    commands([
            encap(zwave.switchBinaryV1.switchBinaryGet(), 1),
            encap(zwave.switchBinaryV1.switchBinaryGet(), 2),
    ])
}

def ping() {
    log.debug "ping()"
    refresh()
}

def installed() {
    refresh()
}

def configure() {
    log.debug "configure()"
    def cmds = initialize()
    commands(cmds)
}

def integer2Cmd(value, size) {
    try{
        switch(size) {
            case 1:
                [value]
                break
            case 2:
                def short value1   = value & 0xFF
                def short value2 = (value >> 8) & 0xFF
                [value2, value1]
                break
            case 3:
                def short value1   = value & 0xFF
                def short value2 = (value >> 8) & 0xFF
                def short value3 = (value >> 16) & 0xFF
                [value3, value2, value1]
                break
            case 4:
                def short value1 = value & 0xFF
                def short value2 = (value >> 8) & 0xFF
                def short value3 = (value >> 16) & 0xFF
                def short value4 = (value >> 24) & 0xFF
                [value4, value3, value2, value1]
                break
        }
    } catch (e) {
        log.debug "Error: integer2Cmd $e Value: $value"
    }
}

def updated() {
    if (!state.lastRan || now() >= state.lastRan + 2000) {
        log.debug "updated()"
        state.lastRan = now()
        def cmds = initialize()
        commands(cmds)
    } else {
        log.debug "updated() ran within the last 2 seconds. Skipping execution."
    }
}

def initialize() {
    log.debug "initialize()"
    if (!childDevices) {
        createChildDevices()
    } else if (device.label != state.oldLabel) {
        childDevices.each {
            if (it.label == "${state.oldLabel} (CH${channelNumber(it.deviceNetworkId)})") {
                def newLabel = "${device.displayName} (CH${channelNumber(it.deviceNetworkId)})"
                it.setLabel(newLabel)
            }
        }
        state.oldLabel = device.label
    }
    sendEvent(name: "checkInterval", value: 3 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
    sendEvent(name: "numberOfButtons", value: 1, displayed: true)
    def cmds = processAssociations()
    cmds << zwave.configurationV1.configurationSet(scaledConfigurationValue: ledIndicator!=null? ledIndicator.toInteger() : 0, parameterNumber: 1, size: 1)
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 1)
    cmds << zwave.configurationV1.configurationSet(configurationValue: autoOff1!=null? integer2Cmd(autoOff1.toInteger(), 2) : integer2Cmd(0,2), parameterNumber: 2, size: 2)
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 2)
    cmds << zwave.configurationV1.configurationSet(configurationValue: autoOff2!=null? integer2Cmd(autoOff2.toInteger(), 2) : integer2Cmd(0,2), parameterNumber: 3, size: 2)
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 3)
    return cmds
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    log.debug "${device.displayName} parameter '${cmd.parameterNumber}' with a byte size of '${cmd.size}' is set to '${cmd.configurationValue}'"
}

private encap(cmd, endpoint) {
    if (endpoint) {
        zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint: endpoint).encapsulate(cmd)
    } else {
        cmd
    }
}

private command(hubitat.zwave.Command cmd) {
    if (state.sec) {
        zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
        cmd.format()
    }
}

private commands(commands, delay = 1000) {
    delayBetween(commands.collect {
        command(it)
    }, delay)
}

private channelNumber(String dni) {
    dni.split("-ep")[-1] as Integer
}
private void createChildDevices() {
    state.oldLabel = device.label
    for (i in 1..2) {
        addChildDevice("Switch Child Device", "${device.deviceNetworkId}-ep${i}", [completedSetup: true, label: "${device.displayName} (CH${i})",
                                                                                   isComponent: false, componentName: "ep$i", componentLabel: "Channel $i"
        ])
    }
}

def setDefaultAssociations() {
    def smartThingsHubID = String.format('%02x', zwaveHubNodeId).toUpperCase()
    state.defaultG1 = [smartThingsHubID]
    state.defaultG2 = []
    state.defaultG3 = []
}

def setAssociationGroup(group, nodes, action, endpoint = null){
    // Normalize the arguments to be backwards compatible with the old method
    action = "${action}" == "1" ? "Add" : "${action}" == "0" ? "Remove" : "${action}" // convert 1/0 to Add/Remove
    group  = "${group}" =~ /\d+/ ? (group as int) : group                             // convert group to int (if possible)
    nodes  = [] + nodes ?: [nodes]                                                    // convert to collection if not already a collection

    if (! nodes.every { it =~ /[0-9A-F]+/ }) {
        log.error "invalid Nodes ${nodes}"
        return
    }

    if (group < 1 || group > maxAssociationGroup()) {
        log.error "Association group is invalid 1 <= ${group} <= ${maxAssociationGroup()}"
        return
    }

    def associations = state."desiredAssociation${group}"?:[]
    nodes.each {
        node = "${it}"
        switch (action) {
            case "Remove":
                if (logEnable) log.debug "Removing node ${node} from association group ${group}"
                associations = associations - node
                break
            case "Add":
                if (logEnable) log.debug "Adding node ${node} to association group ${group}"
                associations << node
                break
        }
    }
    state."desiredAssociation${group}" = associations.unique()
    return
}

def maxAssociationGroup(){
    if (!state.associationGroups) {
        if (logEnable) log.debug "Getting supported association groups from device"
        zwave.associationV2.associationGroupingsGet() // execute the update immediately
    }
    (state.associationGroups?: 5) as int
}

def processAssociations(){
    def cmds = []
    setDefaultAssociations()
    def associationGroups = maxAssociationGroup()
    for (int i = 1; i <= associationGroups; i++){
        if(state."actualAssociation${i}" != null){
            if(state."desiredAssociation${i}" != null || state."defaultG${i}") {
                def refreshGroup = false
                ((state."desiredAssociation${i}"? state."desiredAssociation${i}" : [] + state."defaultG${i}") - state."actualAssociation${i}").each {
                    if (logEnable) log.debug "Adding node $it to group $i"
                    cmds << zwave.associationV2.associationSet(groupingIdentifier:i, nodeId:hubitat.helper.HexUtils.hexStringToInt(it))
                    refreshGroup = true
                }
                ((state."actualAssociation${i}" - state."defaultG${i}") - state."desiredAssociation${i}").each {
                    if (logEnable) log.debug "Removing node $it from group $i"
                    cmds << zwave.associationV2.associationRemove(groupingIdentifier:i, nodeId:hubitat.helper.HexUtils.hexStringToInt(it))
                    refreshGroup = true
                }
                if (refreshGroup == true) cmds << zwave.associationV2.associationGet(groupingIdentifier:i)
                else if (logEnable) log.debug "There are no association actions to complete for group $i"
            }
        } else {
            if (logEnable) log.debug "Association info not known for group $i. Requesting info from device."
            cmds << zwave.associationV2.associationGet(groupingIdentifier:i)
        }
    }
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    def temp = []
    if (cmd.nodeId != []) {
        cmd.nodeId.each {
            temp += it.toString().format( '%02x', it.toInteger() ).toUpperCase()
        }
    }
    state."actualAssociation${cmd.groupingIdentifier}" = temp
    log.debug "Associations for Group ${cmd.groupingIdentifier}: ${temp}"
    updateDataValue("associationGroup${cmd.groupingIdentifier}", "$temp")
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    log.debug "Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
    createEvent(name: "groups", value: cmd.supportedGroupings)
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
    log.debug cmd
    if(cmd.applicationVersion && cmd.applicationSubVersion) {
        def firmware = "${cmd.applicationVersion}.${cmd.applicationSubVersion.toString().padLeft(2,'0')}"
        state.needfwUpdate = "false"
        sendEvent(name: "status", value: "fw: ${firmware}")
        updateDataValue("firmware", firmware)
    }
}