<a name="ReadMeAnchor"></a>
<h1>Welcome to sky-nie's Hubitat Repository(For Brand NewOne)</h1>

<hr />

<strong>I'm posting this code on GitHub so that anyone can use it, but this is a private repository so pull requests will be ignored.  If you find a problem or want something added, please post a message on the corresponding topic in the hubitat forum.</strong>
<br>
hpm repository-create repository.json --author="winnie" --githuburl=https://github.com/sky-nie
<br>
hpm manifest-create packageManifest.json --name="Package NewOne" --author="winnie" --version=1.0 --heversion=2.1.9 --datereleased=2022-03-21
<br>
hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/newone/newone-mini-smart-plug.groovy --required=false
<br>
hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/newone/newone-power-meter-plug.groovy --required=false
<br>
hpm manifest-add-driver packageManifest.json --location=https://raw.githubusercontent.com/sky-nie/hubitat/main/newone/newone-smart-plug-dimmer.groovy --required=false
<br>
hpm repository-add-package repository.json --manifest=https://raw.githubusercontent.com/sky-nie/hubitat/main/newone/packageManifest.json --name="Package NewOne" --category=Integrations --description="Package for NewOne"
<hr />