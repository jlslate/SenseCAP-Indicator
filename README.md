# SenseCAP-Indicator for Hubitat

### This project allows you to display anywhere from a 1x1 tile (1 sensor) up to and including a 6x5 (30 sensors) grid of sensors from a Hubitat hub onto a SenseCAP Indicator D1 configured with OpenHASP firmware.

## Requirements

- Hubitat Elevation hub
- SenseCAP Indicator D1 (480×480, running openHASP 0.7+)
- MQTT broker (Hubitat's built-in broker works)

### Here are the brief instructions:
  - Hubitat - install and configure MQTT Export Integration
  - Hubitat - copy driver code from github
  - Hubitat - copy app code from github
  - SenseCAP - update with OpenHASP and connect to WiFi
  - SenseCAP - connect via browser and configure it
  - Hubitat - create a virtual device using the driver
  - Hubitat - Install and configure app

## Install and configure MQTT Export Integration
  - On Hubitat hub, Select Integrations / Add Built-in integration
  - Select MQTT Export Integration (beta)
  - Open Integration and select Use built-in MQTT service at _hubs ip address here_:1882
  - Save the login information displayed. It will be hubitat / _16 character password_
  - Select Done

## Copy driver code from github
  - On github, choose the _SenseCAP-Indictor-Driver.groovy_ file, click on **Raw** and copy the entire text
  - On the Hubitat hub, expand the **FOR DEVELOPERS** menu and select **Drivers Code**
  - Select **+ Add driver** and in the New driver, paste the contents of the copied text
  - Select **Save**

## Copy app code from github
  - On github, choose the _SenseCAP-Indicator-App.groovy_ file, click on **Raw** and copy the entire text
  - On the Hubitat hub, select **Apps code**
  - Select **+ Add app** and in the New app, paste the contents of the copied text
  - Select **Save**
    
## Update SenseCAP with OpenHASP and connect to WiFi
  - Using Chrome browser, go to [openhasp](https://nightly.openhasp.com/), choose SenseCap Indicator D1, and select **INSTALL**
  - Choose the USB Serial option and select **Connect**. Choose **INSTALL OPENHASP**. Select _Erase device_ checkbox\. Select **NEXT**, then **INSTALL**
  - After a couple of minutes,the SenseCAP will display a screen that shows some instructions to connect to it either via WiFi, or as an Access Point. Tap the screen to connect it to your WiFi
  - Enter the Ssid: and Password: and the the check mark on the bottom right
  - If correct, the SenseCAP will reboot and show the current name **_plate_** and IP address of the device. You will need the IP address later, so document it. It is also a good idea to give it a DHCP reservation so the address doesn't change

## Connect to SenseCAP via WiFi and configure it:
  - In a browser, go to the IP address displayed on the SenseCAP
  - Choose **HASP Design**, and select _Material Dark_ for the **UI Theme**
  - Select **Save Settings**
  - Choose **Time Settings** and select your **Region** and **Timezone** and **Save Settings**
  - Choose **MQTT Settings**. You can rename the device by changing the _Hostname*_ field.
  - Broker: Enter the IP address of the Hubitat hub.
  - Username: hubitat
  - Password: _16 character password_
  - Leave everything else the same
  - Select **Save**
  - Select **Main Menu**
  - Select **File Editor**
  - Delete the all the files _**EXCEPT**_ **{}config json** 
  - Select **Save**, then **Home**
  - Select **Restart** to load the new image
  
## Install and Configure the device
  - On the hubitat hub, select **Devices**, then **+ Add device**
  - Select **Virtual**, and choose _SenseCAP Indicator_
  - Select **Next**, give it a name, select **Next**, select a room if so inclined, or **Skip >>** if not.
  - Select **View Device Details**, then **Preferences**.
  - Enter _hubitat_ for the **MQTT Username**
  - Enter the password from the MQTT app for **MQTT Password**
  - Change the **openHASP Node Name** if you changed it on the SenseCAP
  - Modify any of the other fields as required and select **Save**
  - Select **Save** and then select **Commands**
  - You should see _Connected_ on the **Mqtt Status**

## Install and Configure the app
  - On the Hubitat hub, select **Apps** and then **+ Add user app**
  - Choose _SenseCAP Indicator_
  - Choose the Device you created on the **Select your SenseCAP Indicator device** and select **Update**
  - Modify any of the settings on the 
  - Start configuring the Pages and Tiles
  - Select Done. The SenseCAP will reboot with your configuration
### Be patient, it takes a while to populate
