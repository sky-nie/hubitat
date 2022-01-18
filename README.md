<a name="ReadMeAnchor"></a>
<h1>Welcome to sky-nie's Hubitat Repository</h1>

<hr />

<strong>I'm posting this code on GitHub so that anyone can use it, but this is a private repository so pull requests will be ignored.  If you find a problem or want something added, please post a message on the corresponding topic in the hubitat forum.</strong>

hpm repository-create repository.json --author="winnie" --githuburl=https://github.com/sky-nie

hpm repository-add-package repository.json --manifest=https://raw.githubusercontent.com/sky-nie/hubitat/main/packageManifest.json --name="Package Minoston" --category=Integrations --description="Package for Minoston" --tags="Doors & Windows","Temperature & Humidity","Lights & Switches","Energy Monitoring"]

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/minoston-door-window-sensor.src/minoston-door-window-sensor.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/minoston-power-switch.src/minoston-power-switch.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/minoston-wallmote.src/minoston-wallmote.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/mini-smart-plug.src/mini-smart-plug.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/mini-smart-plug-dimmer.src/mini-smart-plug-dimmer.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/mini-smart-plug.src/mini-smart-plug.groovy --required=false

hpm manifest-remove-driver --id=50795455-2814-48a2-9bfa-038c246677eb packageManifest.json

hpm manifest-remove-driver --id=2e4a45d1-f213-48da-aa4c-a8a8458f8dbe packageManifest.json

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/in-wall-smart-switch.src/in-wall-smart-switch.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/in-wall-smart-switch-dimmer.src/in-wall-smart-switch-dimmer.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/min-smart-plug.src/min-smart-plug.groovy --required=false

hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/min-smart-plug-dimmer.src/min-smart-plug-dimmer.groovy --required=false
<hr />
