/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Indicator Driver v2.0.0
 *
 * Hubitat driver for the SenseCAP Indicator D1 (480x480) running openHASP firmware.
 * Communicates via MQTT. Up to 12 pages, each independently configurable.
 *
 * Key features:
 * - Up to 12 pages: smoke / motion / water / contact / lock / garage / light /
 *   thermostat / temperature / humidity / illumination / weather / mixed
 * - Grid auto-sized 2x1 to 5x5 based on device count (2, 4, 6, 9, ... slots)
 * - Tile rearrangement via slot position numbers in app
 * - Page display order adjustable in app
 * - Light tiles: tap-to-toggle via openHASP button events. Lock/garage tiles
 *   split by tap length: short tap is tap-to-navigate (like any other type,
 *   if configured in the app), long-press pops up a Confirm/Cancel msgbox
 *   (showConfirmPopup) that must be confirmed before the toggle fires
 * - Motion/smoke/water/contact: red-to-inactive color fade on clear
 * - Icons centered top, labels bottom-aligned per tile
 * - Temperature/humidity/illumination/weather: centered numeric value tile,
 *   colored by the app's low/high threshold check (setPageXSlotValue)
 * - Clock: Mixed-slot only, no device -- driver ticks its own per-minute
 *   schedule (clockTick) and renders time and/or date, 12/24-hour, no seconds
 * - Pages rendered sequentially off-screen; navigated to only after fully built (no flash)
 * - clearpage all at push start prevents stale objects from previous push
 * - Backlight management: idle timeout, motion-triggered on/off, touch timeout
 * - safePub() wraps all MQTT publishes -- auto-reconnects on drop
 * - Thermostat tap IPC via device event (no hub variable required)
 * - Tap-to-navigate: any type not already tappable (light/lock/garage toggle;
 *   thermostat has its own controls) can jump to another page on tap, then
 *   auto-revert after an adjustable delay (doTapNavigate/tapNavRevert)
 *
 * Changelog:
 * v2.0.0 -- Renamed throughout from "SenseCAP Dashboard and Thermostat" to
 *           "SenseCAP Indicator" (definition's name field and this file's
 *           own header), matching the hardware's own name. Version reset to
 *           2.0.0 to mark this rename as a fresh baseline -- see the entries
 *           below for everything that shipped under the old name and
 *           version numbers.
 * v2.6.0 -- Split lock/garage tap handling by press length: short tap (down
 *           -> up, no "long" in between) now falls through to the same
 *           tap-to-navigate check every other type gets, while a real
 *           long-press (openHASP fires "long" once the hold threshold trips,
 *           before "hold"/"release") triggers showConfirmPopup immediately
 *           instead of waiting for release. Previously any tap on a
 *           lock/garage tile went straight to the confirmation popup.
 * v2.5.0 -- Lock and garage tiles now show a Confirm/Cancel msgbox
 *           (showConfirmPopup, id 210) instead of toggling immediately --
 *           handleButtonTap defers to it for sType lock/garage, stashing
 *           page/slot/type in state until the openHASP msgbox reports which
 *           button was tapped (handleConfirmPopupResponse); only val 0
 *           ("Confirm") fires the existing lightTapped event. Light tiles
 *           are unchanged (still toggle on tap, no confirmation).
 * v2.4.0 -- Compact weather tiles (grids > 4 tiles) now get their own
 *           weatherCompactFontFor/weatherCompactPadTopFor instead of reusing
 *           labelFontFor/labelPadTopFor -- bigger font, and vertically
 *           centered in the space left below the icon rather than pinned to
 *           the bottom like a device-name caption.
 * v2.3.0 -- Weather tiles now collapse to icon + temperature on grids with
 *           more than 4 tiles (weatherWantsFullSummary) -- the full temp/
 *           humidity/pressure/wind block only fits on 1x1/2x1/2x2. Compact
 *           grids reuse labelFontFor/labelPadTopFor like any other
 *           single-line tile; the app sends the shorter text (see its
 *           formatWeatherSummary). Also bumped the 1x1 weather icon and
 *           data fonts (72/36, was 64/32) now that there's a single line
 *           to fit instead of gauging for 5.
 * v2.2.0 -- Tap-to-navigate: added updatePageXTapConfig (x12) so the app can
 *           register a target page + revert delay per slot; every layout
 *           function's "click" property is now computed from that config
 *           (tapClickableFor) instead of a hardcoded false, for every type
 *           not already tappable. handleButtonTap dispatches to
 *           doTapNavigate/tapNavRevert, which pause/resume rotation around
 *           the visit (reuses maybeRestartRotation).
 * v2.1.0 -- Added a 2x1 grid (2 tall side-by-side tiles, for pages with
 *           exactly 2 devices -- was previously stuffed into a 2x2 grid with
 *           2 empty cells) and every per-grid table entry it needs
 *           (maxSlotsForGrid, icon/label/value font+offset tables,
 *           weather font/offset tables, layoutJsonl -> layoutGrid(2,1,...)).
 *           Expanded max pages from 6 to 12: 48 new driver commands + ~70 new
 *           per-page delegate methods (setPageXGridLayout/SlotValue/
 *           ClockConfig/Labels/SlotTypes, setPageXMotionActive/Inactive/
 *           SlotEmpty, pushPageXLayout, navigatePageX), Math.min(12,...)
 *           clamps, and state.numberOfPages fallback defaults all bumped
 *           from 6/6 to 12/12. Along the way, replaced the switch/case page
 *           dispatchers in pushPageLayout()/doNavigate() with dynamic method
 *           names (runIn(secs, "navigatePage${page}")) since those don't
 *           need per-page cases at all -- matches app v2.1.0's equivalent
 *           cleanup.
 *           / weatherDataPadTopFor) was tuned assuming single-line content and
 *           left as little as 32px of room -- too little even for one line,
 *           let alone the 2 lines a wrapped device name or the 5-line weather
 *           block need. Recomputed both against actual line-height * line-count
 *           + margin for every grid size so wrapped/multi-line text fits.
 * v2.0.0 -- Numeric/weather/clock labels moved off the tile's own "btn.text"
 *           + pad_top entirely, onto a new companion "label" object per slot
 *           (id = slot+100), positioned with fixed x/y at layout-creation
 *           time (labelObjectJsonl). pad_top still didn't move anything even
 *           after the v1.9.1 baseline fix, so rather than keep guessing at a
 *           property whose behavior isn't documented for later patches, this
 *           uses only mechanisms already proven reliable in this codebase:
 *           fixed creation-time coordinates + text-only updates. Icon+label
 *           tiles (motion/smoke/etc) are untouched -- still use btn.text.
 * v1.9.1 -- Fix: layout1x1/2x2/3x3/4x4/layoutNxN created their "btn" objects
 *           with a smaller, DIFFERENT pad_top than labelPadTopFor uses for
 *           later updates (e.g. layout2x2 baked in 106 vs labelPadTopFor's
 *           160) -- openHASP doesn't reliably re-apply pad_top via a later
 *           property-only patch, so this creation-time value is what
 *           actually governed label position. Fixed all 5 to match
 *           labelPadTopFor. Also pushed numericLabelPadTopFor and the
 *           weather font/pad_top noticeably further this round -- the
 *           previous changes were too subtle to see.
 * v1.9.0 -- Added numericLabelPadTopFor (regular numeric tiles' device-name
 *           label now sits lower than labelPadTopFor, kept separate so the
 *           existing icon+label tiles are unaffected). Weather's data font
 *           bumped up slightly and pad_top adjusted for its now-5-line block
 *           (Temp/Humidity/Pressure/Wind Speed/Wind Direction).
 * v1.8.1 -- Weather's data block moved further down (weatherDataPadTopFor,
 *           matching labelPadTopFor's bottom-anchoring proportion) with a
 *           smaller font (weatherDataFontFor) to fit the 4th line (Temp)
 *           the app now sends alongside Humidity/Pressure/Wind.
 * v1.8.0 -- Weather now shows a condition icon (weatherIconGlyph, real MDI
 *           codepoints from the openHASP 0.7 font table) plus a curated
 *           humidity/pressure/wind data block, instead of a text-only dump.
 *           setPageXSlotValue takes a 4th "iconKey" param (Weather only,
 *           "" for other types); renderWeatherTile lays out icon-on-top +
 *           data-block-below instead of the old single-block dump.
 * v1.7.1 -- Weather tiles now get a dedicated dense renderer (renderWeatherTile /
 *           weatherFontFor): no separate label region, much smaller font, full
 *           tile height -- needed once Weather started sending many lines of
 *           data instead of a single value (see app v1.7.1 for the data-side fix).
 * v1.7.0 -- Per-type below/above threshold colors: Temperature blue/orange,
 *           Humidity brown/blue, Illumination black/yellow; all numeric types
 *           share one Slate color when within range (setPageXSlotValue's
 *           3rd param is now "below"/"within"/"above", not a boolean). Weather
 *           tiles no longer show the device name label and instead display
 *           every current attribute on the device.
 * v1.6.2 -- Fix: numeric/clock value tiles reused iconFontFor() (sized for a
 *           single 1-2 char icon glyph) for the value text, which was too
 *           large for longer strings like "72.5°" or "3:45 PM" -- it wrapped
 *           and collided with the bottom label. Added valueFontFor() (smaller)
 *           and valueOffsetYFor() (lifts the value above center) so the value
 *           and label no longer overlap.
 * v1.6.1 -- Fix: pushPageLayout's initial per-slot render (right after the
 *           grid is built) never handled the new numeric/clock types, so
 *           they fell through to the default motion icon until something
 *           else happened to overwrite them. Numeric/clock slots now render
 *           correctly (placeholder value / live clock) immediately on push.
 * v1.6.0 -- Added temperature/humidity/illumination/weather (numeric value
 *           tiles with low/high threshold coloring) and Clock (Mixed-slot
 *           only, no device, driver-side per-minute tick).
 *
 * Author: jlslate
 * Version: 2.0.0
 */

import groovy.transform.Field

metadata {
    definition(
        name: "SenseCAP Indicator",
        namespace: "jlslate",
        author: "jlslate (slate)",
        description: "Auto-paged sensor monitor -- Smoke/Motion/Water/Contact each get their own page"
    ) {
        capability "Initialize"
        capability "Actuator"

        command "reconnectMqtt"
        command "rebootDisplay"
        command "pushAllLayouts", [[name:"numberOfPages", type:"NUMBER"]]
        command "setNumberOfPages", [[name:"n", type:"NUMBER"]]

        command "setPage1GridLayout", [[name:"g", type:"STRING"]]
        command "setPage2GridLayout", [[name:"g", type:"STRING"]]
        command "setPage3GridLayout", [[name:"g", type:"STRING"]]
        command "setPage4GridLayout", [[name:"g", type:"STRING"]]
        command "setPage5GridLayout", [[name:"g", type:"STRING"]]
        command "setPage6GridLayout", [[name:"g", type:"STRING"]]
        command "setPage7GridLayout", [[name:"g", type:"STRING"]]
        command "setPage8GridLayout", [[name:"g", type:"STRING"]]
        command "setPage9GridLayout", [[name:"g", type:"STRING"]]
        command "setPage10GridLayout", [[name:"g", type:"STRING"]]
        command "setPage11GridLayout", [[name:"g", type:"STRING"]]
        command "setPage12GridLayout", [[name:"g", type:"STRING"]]

        command "setPage1MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage7MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage7MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage7SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage8MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage8MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage8SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage9MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage9MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage9SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage10MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage10MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage10SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage11MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage11MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage11SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage12MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage12MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage12SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]

        // Numeric value tiles (temperature/humidity/illumination/weather) -- app
        // pushes the formatted display text plus whether it's within the low/high
        // threshold; driver just renders (no icon lookup involved).
        command "setPage1SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage2SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage3SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage4SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage5SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage6SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage7SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage8SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage9SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage10SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage11SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]
        command "setPage12SlotValue", [[name:"sensorIndex", type:"NUMBER"], [name:"text", type:"STRING"], [name:"rangeState", type:"STRING"], [name:"iconKey", type:"STRING"]]

        // Clock slots (Mixed pages only, no device) -- driver ticks its own
        // per-minute schedule and re-renders time/date locally.
        command "updatePage1ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage2ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage3ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage4ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage5ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage6ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage7ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage8ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage9ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage10ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage11ClockConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage12ClockConfig", [[name:"config", type:"JSON_OBJECT"]]

        // Tap-to-navigate (any type not already tappable) -- app resolves the
        // configured target's current display position and sends it here;
        // driver navigates there on tap, then reverts after revertSeconds.
        command "updatePage1TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage2TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage3TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage4TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage5TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage6TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage7TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage8TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage9TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage10TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage11TapConfig", [[name:"config", type:"JSON_OBJECT"]]
        command "updatePage12TapConfig", [[name:"config", type:"JSON_OBJECT"]]

        command "updatePage1Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage2Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage3Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage4Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage5Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage6Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage7Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage8Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage9Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage10Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage11Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage12Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage1SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage2SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage3SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage4SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage5SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage6SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage7SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage8SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage9SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage10SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage11SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage12SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]

        // Thermostat commands (one set per page -- app calls these directly)
        (1..6).each { pg ->
            command "updateThermostatDisplay",    [[name:"page", type:"NUMBER"], [name:"data", type:"JSON_OBJECT"]]
        }
        attribute "thermostatTapped", "string"

        // Register thermostat device so driver can handle taps directly

        attribute "mqttStatus",        "string"
        attribute "displayRebooted",   "string"
        attribute "layoutPushComplete","string"
        attribute "pushInProgress",    "string"
        attribute "lightTapped",       "string"

        (1..12).each { pg ->
            attribute "page${pg}GridLayout", "string"
        }
    }

    preferences {
        input name: "mqttBroker",   type: "text",     title: "<b>MQTT Broker</b> (host:port)", required: true, defaultValue: "tcp://127.0.0.1:1883"
        input name: "mqttPassword", type: "password", title: "MQTT Password", required: true,
              description: "Found in Hubitat -> Integrations -> MQTT Broker"
        input name: "mqttClientId", type: "text",     title: "MQTT Client ID", required: true, defaultValue: "hubitat-sensecap-dashboard"
        input name: "haspNode",     type: "text",     title: "<b>openHASP Node Name</b>", required: true, defaultValue: "plate"

        input name: "colorActive",        type: "enum", title: "<b>Active color</b>",     options: activeColorOptions(), defaultValue: "#FF0000", required: true
        input name: "colorInactive",      type: "enum", title: "Inactive -- Motion",       options: colorOptions(),       defaultValue: "#008000", required: true
        input name: "colorDoorInactive",    type: "enum", title: "Inactive -- Door",         options: colorOptions(),       defaultValue: "#00FFFF", required: true
        input name: "colorWindowInactive",  type: "enum", title: "Inactive -- Window",       options: colorOptions(),       defaultValue: "#00FFFF", required: true
        input name: "colorContactInactive", type: "enum", title: "Inactive -- Contact",      options: colorOptions(),       defaultValue: "#00FFFF", required: true
        input name: "colorWaterInactive", type: "enum", title: "Inactive -- Water",        options: colorOptions(),       defaultValue: "#0000FF", required: true
        input name: "colorSmokeInactive", type: "enum", title: "Inactive -- Smoke",        options: colorOptions(),       defaultValue: "#808080", required: true
        input name: "colorLightInactive", type: "enum", title: "Inactive -- Light off",    options: colorOptions(),       defaultValue: "#808080", required: true
        input name: "colorLightActive",   type: "enum", title: "Active -- Light on",       options: colorOptions(),       defaultValue: "#FFFF00", required: true
        input name: "colorLockInactive",  type: "enum", title: "Inactive -- Lock closed",  options: colorOptions(),       defaultValue: "#1B5E20", required: true
        input name: "colorLockOpen",      type: "enum", title: "Active -- Lock open",      options: colorOptions(),       defaultValue: "#E65100", required: true
        input name: "colorGarageInactive",type: "enum", title: "Inactive -- Garage closed",options: colorOptions(),       defaultValue: "#1B5E20", required: true
        input name: "colorGarageOpen",    type: "enum", title: "Active -- Garage open",    options: colorOptions(),       defaultValue: "#E65100", required: true
        input name: "colorNumericInRange",    type: "enum", title: "<b>In range</b> -- Temp/Humidity/Illumination/Weather", options: colorOptions(), defaultValue: "#708090", required: true
        input name: "colorTemperatureBelow",  type: "enum", title: "Below low -- Temperature",   options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorTemperatureAbove",  type: "enum", title: "Above high -- Temperature",  options: colorOptions(), defaultValue: "#FF8C00", required: true
        input name: "colorHumidityBelow",     type: "enum", title: "Below low -- Humidity",      options: colorOptions(), defaultValue: "#8B4513", required: true
        input name: "colorHumidityAbove",     type: "enum", title: "Above high -- Humidity",     options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorIlluminationBelow", type: "enum", title: "Below low -- Illumination",  options: colorOptions(), defaultValue: "#000000", required: true
        input name: "colorIlluminationAbove", type: "enum", title: "Above high -- Illumination", options: colorOptions(), defaultValue: "#FFFF00", required: true
        input name: "colorClockBackground",   type: "enum", title: "Background -- Clock",        options: colorOptions(), defaultValue: "#000000", required: true

        input name: "colorThermostatHeating", type: "enum", title: "Thermostat -- Heating",     options: colorOptions(), defaultValue: "#FF6600", required: true
        input name: "colorThermostatCooling", type: "enum", title: "Thermostat -- Cooling",     options: colorOptions(), defaultValue: "#0088FF", required: true
        input name: "colorThermostatIdle",    type: "enum", title: "Thermostat -- Idle",        options: colorOptions(), defaultValue: "#606060", required: true
        input name: "colorThermostatOff",     type: "enum", title: "Thermostat -- Off",         options: colorOptions(), defaultValue: "#303030", required: true
        input name: "colorThermostatFan",     type: "enum", title: "Thermostat -- Fan only",    options: colorOptions(), defaultValue: "#008080", required: true

        input name: "fadeDuration",           type: "number", title: "Fade duration (seconds)",                                              defaultValue: 30,  required: true
        input name: "showPageIndicator",      type: "bool",   title: "Show page indicator (e.g. 1/4)",                                      defaultValue: true
        input name: "rotationInterval",       type: "number", title: "Auto-scroll pages every (seconds, 0 = off)",                          defaultValue: 10
        input name: "backlightOnMotion",      type: "bool",   title: "<b>Backlight ON</b> when sensor active",                              defaultValue: true
        input name: "backlightOffDelay",      type: "number", title: "Backlight OFF after all clear (seconds, 0=never)",                    defaultValue: 0
        input name: "motionBacklightTimeout", type: "number", title: "Backlight OFF after active for (seconds, 0=never)",                   defaultValue: 60
        input name: "touchBacklightTimeout",  type: "number", title: "Backlight OFF after screen tap (seconds, 0=never)",                   defaultValue: 30
        input name: "idleTimeout",            type: "number", title: "Blank display after idle (seconds, 0=never)",                                                                    defaultValue: 300
        input name: "logLevel",               type: "enum",   title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true

        // Thermostat devices -- one per page, set automatically by the app
        input name: "thermostatPage1", type: "capability.thermostat", title: "Thermostat -- Page 1", required: false, multiple: false
        input name: "thermostatPage2", type: "capability.thermostat", title: "Thermostat -- Page 2", required: false, multiple: false
        input name: "thermostatPage3", type: "capability.thermostat", title: "Thermostat -- Page 3", required: false, multiple: false
        input name: "thermostatPage4", type: "capability.thermostat", title: "Thermostat -- Page 4", required: false, multiple: false
        input name: "thermostatPage5", type: "capability.thermostat", title: "Thermostat -- Page 5", required: false, multiple: false
        input name: "thermostatPage6", type: "capability.thermostat", title: "Thermostat -- Page 6", required: false, multiple: false
        input name: "thermostatPage7", type: "capability.thermostat", title: "Thermostat -- Page 7", required: false, multiple: false
        input name: "thermostatPage8", type: "capability.thermostat", title: "Thermostat -- Page 8", required: false, multiple: false
        input name: "thermostatPage9", type: "capability.thermostat", title: "Thermostat -- Page 9", required: false, multiple: false
        input name: "thermostatPage10", type: "capability.thermostat", title: "Thermostat -- Page 10", required: false, multiple: false
        input name: "thermostatPage11", type: "capability.thermostat", title: "Thermostat -- Page 11", required: false, multiple: false
        input name: "thermostatPage12", type: "capability.thermostat", title: "Thermostat -- Page 12", required: false, multiple: false
    }
}

private Map activeColorOptions() {
    ["#FF0000":"Red","#FF4500":"Orange-red","#FF8C00":"Dark orange","#FF1493":"Deep pink",
     "#8B0000":"Dark red","#FF6347":"Tomato","#DC143C":"Crimson","#FF0080":"Hot magenta"]
}

private Map colorOptions() {
    ["#000000":"Black","#708090":"Slate","#8B4513":"Brown","#FF8C00":"Orange",
     "#F8F8FF":"Ghost White","#D3D3D3":"Light Gray","#808080":"Gray","#800000":"Maroon",
     "#FF00FF":"Magenta","#800080":"Purple","#0000FF":"Blue","#000080":"Navy","#00FFFF":"Cyan",
     "#008080":"Teal","#00FF00":"Lime","#008000":"Green","#FFFF00":"Yellow","#808000":"Olive"]
}

// ── Object ID helpers ──────────────────────────────────────────────────────────

private int bgId(int slot)   { slot }
// Companion label object per slot (101-130) -- kept separate from the tile's
// own "btn" object (1-30) so its text can be updated without touching
// pad_top, which openHASP doesn't reliably re-apply via a later patch.
private int labelObjId(int slot) { slot + 100 }
// Confirmation popup (msgbox) for lock/garage taps -- well clear of tile
// ids (1-30), label ids (101-130), and nav/indicator ids (200-202).
private int confirmMsgboxId() { 210 }

// ── State key helpers ──────────────────────────────────────────────────────────

private String stateKey(int page, int idx)      { "p${page}sensor${idx}" }
private String typeKey(int page, int idx)       { "p${page}slotType${idx}" }
private String labelKey(int page, int idx)      { "p${page}label${idx}" }
private String valueTextKey(int page, int idx)  { "p${page}value${idx}" }
private String rangeStateKey(int page, int idx) { "p${page}range${idx}" }   // "below"/"within"/"above"
private String wxIconStateKey(int page, int idx) { "p${page}wxIcon${idx}" } // Weather condition key
private String tapTargetKey(int page, int idx)  { "p${page}tapTarget${idx}" } // Tap-to-navigate target dispPage
private String tapRevertKey(int page, int idx)  { "p${page}tapRevert${idx}" } // Tap-to-navigate revert seconds

// True if this slot has a tap-to-navigate target configured -- used to set
// the tile's "click" property so it's interactive (light/lock/garage/
// thermostat manage their own clickability separately and never hit this).
private boolean tapClickableFor(int page, int idx) {
    return state[tapTargetKey(page, idx)] != null
}

// Temperature/humidity/illumination/weather all render as a centered numeric
// value tile with low/high threshold coloring (no icon lookup).
private boolean isNumericType(String t) {
    return (t == "temperature" || t == "humidity" || t == "illumination" || t == "weather")
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    infoLog "[Dashboard] Driver installed"
    initialize()
}

def updated() {
    infoLog "[Dashboard] Preferences updated"
    initialize()
}

def initialize() {
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[Dashboard] MQTT already connected -- skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery1Minute("sendHeartbeat")
    unschedule("clockTick")
    runEvery1Minute("clockTick")
    scheduleIdleTimeout()
}

def uninstalled() {
    disconnectMqtt()
}

// ── Grid config ────────────────────────────────────────────────────────────────

def setNumberOfPages(n) {
    int num = Math.min(12, Math.max(1, (n as int)))
    state.numberOfPages = num
    infoLog "[Dashboard] Number of pages set to ${num}"
}

def setPage1GridLayout(String g) {
    applyGridLayout(1, g)
}

def setPage2GridLayout(String g) {
    applyGridLayout(2, g)
}

def setPage3GridLayout(String g) {
    applyGridLayout(3, g)
}

def setPage4GridLayout(String g) {
    applyGridLayout(4, g)
}

def setPage5GridLayout(String g) {
    applyGridLayout(5, g)
}

def setPage6GridLayout(String g) {
    applyGridLayout(6, g)
}

def setPage7GridLayout(String g) {
    applyGridLayout(7, g)
}

def setPage8GridLayout(String g) {
    applyGridLayout(8, g)
}

def setPage9GridLayout(String g) {
    applyGridLayout(9, g)
}

def setPage10GridLayout(String g) {
    applyGridLayout(10, g)
}

def setPage11GridLayout(String g) {
    applyGridLayout(11, g)
}

def setPage12GridLayout(String g) {
    applyGridLayout(12, g)
}

private void applyGridLayout(int page, String g) {
    state["page${page}GridLayout"] = g
    state["page${page}MaxSlots"] = maxSlotsForGrid(g)
    sendEvent(name: "page${page}GridLayout", value: g)
    infoLog "[Dashboard] Page ${page} grid -> ${g}"
    (1..30).each { s -> state.remove("p${page}label${s}") }
}

private int maxSlotsForGrid(String g) {
    switch (g) {
        case "thermostat": return 4
        case "1x1": return 1
        case "2x1": return 2
        case "3x3": return 9
        case "4x4": return 16
        case "5x5": return 25
        case "6x5": return 30
        case "3x2": return 6
        case "4x3": return 12
        case "5x4": return 20
        default:    return 4   // 2x2
    }
}

private String activeGrid(int page) {
    return (state["page${page}GridLayout"] ?: "2x2") as String
}

private int maxSensors(int page) {
    int stored = (state["page${page}MaxSlots"] ?: 0) as int
    if (stored > 0) return stored
    return maxSlotsForGrid(activeGrid(page))
}


// Icon font size for the value_str overlay on each grid size
private int iconFontFor(String grid) {
    switch (grid) {
        case "1x1": return 48
        case "2x1": return 40
        case "2x2": return 40
        case "3x3": return 32
        case "4x4": return 24
        case "5x5": return 16
        case "6x5": return 16
        case "3x2": return 32
        case "4x3": return 24
        case "5x4": return 16
        default:    return 32
    }
}

// Icon in value_str, upper-left of tile.
// Offsets are from object center: negative x = left, negative y = up.
private int[] iconOffsetsFor(String grid) {
    int tileW, tileH, iconFont
    switch (grid) {
        case "1x1": tileW=476; tileH=476; iconFont=48; break
        case "2x1": tileW=236; tileH=476; iconFont=40; break
        case "2x2": tileW=234; tileH=236; iconFont=40; break
        case "3x3": tileW=154; tileH=157; iconFont=32; break
        case "4x4": tileW=117; tileH=117; iconFont=24; break
        case "5x5": tileW= 94; tileH= 94; iconFont=16; break
        case "6x5": tileW= 77; tileH= 94; iconFont=16; break
        case "3x2": tileW=157; tileH=237; iconFont=32; break
        case "4x3": tileW=117; tileH=157; iconFont=24; break
        case "5x4": tileW= 93; tileH=117; iconFont=16; break
        default:    tileW=154; tileH=157; iconFont=32
    }
    int pad = 6
    int ofsX = (pad + (int)(iconFont / 2)) - (int)(tileW / 2)
    int ofsY = (pad + (int)(iconFont / 2)) - (int)(tileH / 2)
    return [ofsX, ofsY] as int[]
}

// Smaller font for the label
private int labelFontFor(String grid) {
    switch (grid) {
        case "1x1": return 28
        case "2x1": return 28
        case "2x2": return 28
        case "3x3": return 24
        case "4x4": return 20
        case "5x5": return 12
        case "6x5": return 12
        case "3x2": return 24
        case "4x3": return 20
        case "5x4": return 14
        default:    return 16
    }
}

private int labelFontFor(String grid, String label) { return labelFontFor(grid) }

// pad_top pushes the label toward the bottom of the tile
private int labelPadTopFor(String grid) {
    switch (grid) {
        case "1x1": return 380
        case "2x1": return 380
        case "2x2": return 160
        case "3x3": return 105
        case "4x4": return  78
        case "5x5": return  60
        case "6x5": return  58
        case "3x2": return 180
        case "4x3": return 105
        case "5x4": return  85
        default:    return 105
    }
}

// All MQTT publishes go through safePub.
// On failure, swallow silently. mqttClientStatus fires exactly once when the
// broker drops and handles reconnect. Any sendEvent or runIn here floods the
// log and prevents the reconnect timer from ever firing.
private void safePub(String topic, String payload, int qos = 1, boolean retained = false) {
    try {
        interfaces.mqtt.publish(topic, payload, qos, retained)
    } catch (Exception e) {
        // Silent -- mqttClientStatus handles reconnect
    }
}

def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[Dashboard] MQTT password not set"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String baseId   = settings.mqttClientId ?: "hubitat-sensecap-dashboard"
        String clientId = baseId + "-" + device.id
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[Dashboard] MQTT connected -> ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/+")
        interfaces.mqtt.subscribe("hasp/${node}/event/#")
        infoLog "[Dashboard] Subscribed -- node: ${node}"
        pushIdleConfig()
        // Resume any push that was deferred due to disconnect
        if (state.deferredPages) {
            infoLog "[Dashboard] Resuming deferred push (${state.deferredPages} pages)"
            runIn(3, "resumeDeferredPush")
        }
    } catch (Exception e) {
        infoLog "[Dashboard] ERROR -- MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def resumeDeferredPush() {
    int np = (state.deferredPages ?: 0) as int
    if (np < 1) return
    infoLog "[Dashboard] Resuming deferred pushAllLayouts (${np} pages)"
    pushAllLayouts(np)
}

def reconnectMqtt() {
    disconnectMqtt()
    pauseExecution(2000)
    connectMqtt()
}

def rebootDisplay() {
    String node = settings.haspNode ?: "plate"
    infoLog "[Dashboard] Sending reboot command to display"
    safePub("hasp/${node}/command", "reboot")
}

def mqttClientStatus(String status) {
    infoLog "[Dashboard] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) {
        infoLog "[Dashboard] MQTT lost -- reconnecting in 5s"
        runIn(5, "connectMqtt")
    }
}

def sendHeartbeat() {
    state.lastHeartbeatMs = now()
    boolean connected = false
    try { connected = interfaces.mqtt.isConnected() } catch (Exception e) { connected = false }
    if (!connected) {
        infoLog "[Dashboard] Heartbeat: MQTT not connected -- reconnecting"
        connectMqtt()
        return
    }
    // NOTE: Do NOT send statusupdate here -- it resets the display's idle timer,
    // preventing the backlight from ever blanking. MQTT keepalive handles connection health.
}

// ── MQTT parse ─────────────────────────────────────────────────────────────────

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) {
            log.warn "[Dashboard] Node name mismatch! Device is '${actualNode}' but preference is '${configNode}'"
            sendEvent(name: "mqttStatus", value: "Wrong node name -- should be '${actualNode}'")
        }
        if (msg.payload?.trim() == "online") {
            infoLog "[Dashboard] LWT online (${actualNode}) -- display rebooted, pushing all layouts"
            state.pushInProgress     = false
            state.suppressNavigation = false
            sendEvent(name: "pushInProgress", value: "false")
            unschedule("rotatePage")
            unschedule("returnToPage1AndStartRotation")
            if (!state.lwtPending) {
                state.lwtPending = true
                runIn(5, "fireDisplayRebooted")
            }
        }
        return
    }

    if (msg.topic.contains("statusupdate")) {
        // Only process statusupdate from our configured node
        String updNode = msg.topic.split("/")[1]
        if (updNode != (settings.haspNode ?: "plate")) return
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                // Guard: if we just finished a push recently, the low uptime is from the
                // display that was already up during our push -- not a fresh reboot.
                // Triggering another full push here causes the visible second render.
                long msSincePush = now() - (state.lastPushMs ?: 0L)
                if (msSincePush < 120000) {
                    infoLog "[Dashboard] Ignoring low-uptime statusupdate (${uptime}s) -- push completed ${msSincePush}ms ago, not a reboot"
                    return
                }
                debugLog "[Dashboard] Display rebooted (uptime ${uptime}s) -- scheduling push"
                state.pushInProgress     = false
                state.suppressNavigation = false
                unschedule("rotatePage")
                unschedule("returnToPage1AndStartRotation")
                runIn(5, "fireDisplayRebooted")
            } else {
                debugLog "[Dashboard] Display woke from idle"
                startBacklightTimer()
            }
        } catch (Exception e) { infoLog "[Dashboard] WARN -- Could not parse statusupdate: ${e.message}" }
        return
    }

    if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        if (msg.topic.split("/")[1] != (settings.haspNode ?: "plate")) return
        String v = msg.payload?.trim()
        if (v == "long") {
            if (!allInactive()) return
            state.screenIdle = true
            infoLog "[Dashboard] Display idle (long) -- blanking"
            publishBacklight(false)
        } else if (v == "off") {
            long ms = now() - (state.lastHeartbeatMs ?: 0L)
            if (ms >= 3000) {
                state.screenIdle = false
                debugLog "[Dashboard] Screen woke from touch"
                startBacklightTimer()
            }
        }
        return
    }

    if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        if (msg.topic.split("/")[1] != (settings.haspNode ?: "plate")) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off") { state.screenIdle = true }
            else if (json.state == "on" && state.screenIdle) { state.screenIdle = false; startBacklightTimer() }
        } catch (Exception e) { if (msg.payload?.trim() == "off") state.screenIdle = true }
        return
    }

    String cfgNode = settings.haspNode ?: "plate"
    if (msg.topic.contains("/state/p") && msg.topic.contains("b") && msg.topic.contains(cfgNode)) {
        infoLog "[Dashboard] Button topic: ${msg.topic} payload: ${msg.payload}"
        handleButtonTap(msg.topic, msg.payload)
        return
    }
}

// ── Button tap handler ─────────────────────────────────────────────────────────

private void handleButtonTap(String topic, String payload) {
    def matcher = topic =~ /state\/p(\d+)b(\d+)$/
    if (!matcher) return
    int page  = matcher[0][1] as int
    int btnId = matcher[0][2] as int

    if (btnId == confirmMsgboxId()) {
        handleConfirmPopupResponse(payload)
        return
    }
    if (btnId < 1 || btnId > 30) return

    int slot = btnId
    String sType = state[typeKey(page, slot)] ?: "none"

    // openHASP's push-button sequence for a genuine long-press is
    // down -> long -> hold (repeated) -> release -- "up" never fires, so a
    // plain short tap (down -> up) can never trip this. Fire on "long"
    // (as soon as the hold threshold is reached) rather than waiting for
    // "release" so the popup feels responsive to the still-held finger.
    if ((sType == "lock" || sType == "garage") && payload?.contains('"long"')) {
        debugLog "[Dashboard] Long tap on ${sType}: page ${page} slot ${slot} -- asking for confirmation"
        showConfirmPopup(page, slot, sType)
        return
    }
    if (!payload?.contains('"up"')) return

    if (sType == "light") {
        debugLog "[Dashboard] Tappable tile tapped (${sType}): page ${page} slot ${slot}"
        sendEvent(name: "lightTapped", value: "${page},${slot},${now()}")
        return
    }
    if (sType == "thermostat") {
        debugLog "[Dashboard] Thermostat tile tapped: page ${page} slot ${slot}"
        handleThermostatTap(page, slot)
        return
    }

    Integer tapTarget = state[tapTargetKey(page, slot)] as Integer
    if (tapTarget != null) {
        int revertSecs = (state[tapRevertKey(page, slot)] ?: 10) as int
        debugLog "[Dashboard] Tap-nav tile tapped (${sType}): page ${page} slot ${slot} -> page ${tapTarget}, revert in ${revertSecs}s"
        doTapNavigate(tapTarget, revertSecs)
    }
}

// ── Lock/garage tap confirmation ────────────────────────────────────────────────

// Pops up a Confirm/Cancel msgbox on the tile's own page. Only reached via
// a long-press (handleButtonTap) -- a short tap on lock/garage now goes
// through the normal tap-to-navigate path instead, since a quick tap is too
// easy to trigger by accident for something as consequential as a door.
// Light tiles are unaffected and still toggle straight away on a short tap.
private void showConfirmPopup(int page, int slot, String sType) {
    String node = settings.haspNode ?: "plate"
    String label = state[labelKey(page, slot)] ?: (sType == "garage" ? "garage door" : "lock")
    boolean isActive = (state[stateKey(page, slot)] ?: "inactive") == "active"
    String question = (sType == "lock")
        ? (isActive ? "Lock ${label}?" : "Unlock ${label}?")
        : (isActive ? "Close ${label}?" : "Open ${label}?")

    state.confirmPage = page
    state.confirmSlot = slot
    state.confirmType = sType

    String escaped = labelToJsonEscape(question)
    String jsonl = '{"page":' + page + ',"id":' + confirmMsgboxId() +
        ',"obj":"msgbox","text":"' + escaped + '","options":["Confirm","Cancel"]}'
    safePub("hasp/" + node + "/command/jsonl", jsonl)
}

// msgbox reports which button was tapped as {"event":"up","val":<index>} --
// val 0 is "Confirm" (first entry in the options array), anything else
// (Cancel, or the box being dismissed) does nothing.
private void handleConfirmPopupResponse(String payload) {
    if (!payload?.contains('"up"')) return
    int page = (state.confirmPage ?: 0) as int
    int slot = (state.confirmSlot ?: 0) as int
    String sType = state.confirmType as String
    state.remove("confirmPage")
    state.remove("confirmSlot")
    state.remove("confirmType")
    if (page < 1 || slot < 1 || !sType) return

    Integer val = null
    try {
        val = (new groovy.json.JsonSlurper().parseText(payload)).val as Integer
    } catch (Exception e) { return }

    if (val == 0) {
        debugLog "[Dashboard] Confirm-popup confirmed: page ${page} slot ${slot} (${sType})"
        sendEvent(name: "lightTapped", value: "${page},${slot},${now()}")
    } else {
        debugLog "[Dashboard] Confirm-popup cancelled: page ${page} slot ${slot} (${sType})"
    }
}

// ── Tap-to-navigate ────────────────────────────────────────────────────────────

// Jumps to targetPage, remembering whatever page was on-screen so
// tapNavRevert can return to it. Pauses rotation while the target page is
// showing and resumes it afterward (via maybeRestartRotation).
private void doTapNavigate(int targetPage, int revertSecs) {
    String node = settings.haspNode ?: "plate"
    if (!state.tapNavActive) {
        state.tapNavReturnPage = state.currentDisplayPage ?: 1
    }
    state.tapNavActive = true
    unschedule("rotatePage")
    unschedule("tapNavRevert")
    safePub("hasp/${node}/command/page", "${targetPage}")
    state.currentDisplayPage = targetPage
    if (revertSecs > 0) {
        runIn(revertSecs, "tapNavRevert")
    }
}

def tapNavRevert() {
    int returnPage = (state.tapNavReturnPage ?: 1) as int
    String node = settings.haspNode ?: "plate"
    infoLog "[Dashboard] Tap-nav reverting to page ${returnPage}"
    safePub("hasp/${node}/command/page", "${returnPage}")
    state.currentDisplayPage = returnPage
    state.tapNavActive = false
    maybeRestartRotation()
}

// ── Thermostat device registration ────────────────────────────────────────────

// ── Thermostat display ────────────────────────────────────────────────────────

// Called by the app whenever any thermostat attribute changes.
// data map keys: temp, heatSetpoint, coolSetpoint, mode, operatingState
def updateThermostatDisplay(page, data) {
    int pg = page as int
    if (!data) return
    String node    = settings.haspNode ?: "plate"

    String temp    = data.temp          ?: "--"
    String heat    = data.heatSetpoint  ?: "--"
    String cool    = data.coolSetpoint  ?: "--"
    String mode    = data.mode          ?: "off"
    String opState = data.operatingState ?: "idle"
    boolean away   = (data.away == "true")

    state["p${pg}thermostatTemp"]    = temp
    state["p${pg}thermostatMode"]    = mode
    state["p${pg}thermostatHeat"]    = heat
    state["p${pg}thermostatCool"]    = cool
    state["p${pg}thermostatOpState"] = opState
    state["p${pg}thermostatAway"]    = away

    pushThermostatTile(pg, node, temp, heat, cool, mode, opState, away)
}

private void handleThermostatTap(int page, int slot) {
    if (slot < 2 || slot > 4) return
    String mode = (state["p${page}thermostatMode"] ?: "off") as String
    String heat = (state["p${page}thermostatHeat"] ?: "68") as String
    String cool = (state["p${page}thermostatCool"] ?: "76") as String
    String val  = "${page},${slot},${mode},${heat},${cool}"
    infoLog "[Dashboard] Thermostat tap page ${page} slot ${slot}: mode=${mode}"
    sendEvent(name: "thermostatTapped", value: val)
}

private void pushThermostatTile(int pg, String node, String temp, String heat, String cool, String mode, String opState, boolean away = false) {
    // Single color applied to all 4 tiles
    String bgColor = away ? "#000000" : thermostatColorForState(opState, mode)
    String fgColor = contrastColor(bgColor)

    // Tile 1: temp + descriptive line2
    boolean activeHeat = (opState == "heating") || (mode == "heat" && (!opState || opState == "idle"))
    boolean activeCool = (opState == "cooling") || (mode == "cool" && (!opState || opState == "idle"))
    try {
        BigDecimal t = temp as BigDecimal; BigDecimal h = heat as BigDecimal; BigDecimal c = cool as BigDecimal
        if (activeHeat && t >= h) activeHeat = false
        if (activeCool && t <= c) activeCool = false
    } catch (Exception e) { }

    String line2
    if (away)                       line2 = "Away"
    else if (activeHeat)            line2 = "Heating to ${heat}°"
    else if (activeCool)            line2 = "Cooling to ${cool}°"
    else if (opState == "fan only") line2 = "Fan only"
    else if (mode == "heat")        line2 = "Heat: ${heat}°"
    else if (mode == "cool")        line2 = "Cool: ${cool}°"
    else                            line2 = "Off"

    debugLog "[Dashboard] Thermostat: page=${pg} temp=${temp} mode=${mode} line2=${line2} state=${opState} away=${away} bg=${bgColor}"

    String plusGlyph  = iconToJsonEscape("")
    String minusGlyph = iconToJsonEscape("")
    boolean btnActive = !away
    String btnFg = btnActive ? fgColor : contrastColor(bgColor)
    String modeLabel = away ? "Away" : (mode == "off" ? "${cool}°\n${heat}°" : mode.capitalize())

    safePub("hasp/" + node + "/command/jsonl",
        '{"page":' + pg + ',"id":1,"bg_color":"' + bgColor + '","text_color":"' + fgColor + '","text_font":32,"text":"' + "${temp}°\n${line2}" + '"}')
    pauseExecution(15)
    safePub("hasp/" + node + "/command/jsonl",
        '{"page":' + pg + ',"id":2,"bg_color":"' + bgColor + '","text_color":"' + btnFg + '","text_font":56,"text":"' + plusGlyph + '","click":' + btnActive + '}')
    pauseExecution(15)
    safePub("hasp/" + node + "/command/jsonl",
        '{"page":' + pg + ',"id":3,"bg_color":"' + bgColor + '","text_color":"' + fgColor + '","text_font":56,"text":"' + modeLabel + '","click":true}')
    pauseExecution(15)
    safePub("hasp/" + node + "/command/jsonl",
        '{"page":' + pg + ',"id":4,"bg_color":"' + bgColor + '","text_color":"' + btnFg + '","text_font":56,"text":"' + minusGlyph + '","click":' + btnActive + '}')
}

private String thermostatColorForState(String opState, String mode) {
    // heating=red, cooling=blue, off/idle=slate, away handled in caller (black)
    if (opState == "heating" || mode == "heat") return "#CC0000"   // red
    if (opState == "cooling" || mode == "cool") return "#0055CC"   // blue
    return "#708090"   // slate -- off or idle
}

// ── Numeric value tiles (temperature/humidity/illumination/weather) ──────────

// Called by the app whenever a numeric sensor's value changes. text is the
// already-formatted display string (e.g. "72°", or -- for Weather -- a
// curated humidity/pressure/wind summary); rangeState is "below"/"within"/
// "above" vs the app's low/high threshold -- the driver just picks a color
// and renders. iconKey (Weather only, "" otherwise) selects a condition
// glyph -- see weatherIconGlyph().
def setPage1SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(1, n as int, text as String, rangeState, iconKey) }
def setPage2SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(2, n as int, text as String, rangeState, iconKey) }
def setPage3SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(3, n as int, text as String, rangeState, iconKey) }
def setPage4SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(4, n as int, text as String, rangeState, iconKey) }
def setPage5SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(5, n as int, text as String, rangeState, iconKey) }
def setPage6SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(6, n as int, text as String, rangeState, iconKey) }
def setPage7SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(7, n as int, text as String, rangeState, iconKey) }
def setPage8SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(8, n as int, text as String, rangeState, iconKey) }
def setPage9SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(9, n as int, text as String, rangeState, iconKey) }
def setPage10SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(10, n as int, text as String, rangeState, iconKey) }
def setPage11SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(11, n as int, text as String, rangeState, iconKey) }
def setPage12SlotValue(n, text, rangeState, iconKey) { setSlotValueForPage(12, n as int, text as String, rangeState, iconKey) }

// Picks the tile background color for a numeric slot given its sensor type
// and where the current value falls relative to the low/high threshold.
// "within" is always the shared Slate color; below/above are per-type so
// e.g. Temperature (blue/orange) and Humidity (brown/blue) can differ.
private String numericColorFor(String sType, String rangeSt) {
    if (rangeSt != "below" && rangeSt != "above") return (settings.colorNumericInRange ?: "#708090")
    switch (sType) {
        case "temperature":
        case "weather":
            return (rangeSt == "below") ? (settings.colorTemperatureBelow  ?: "#0000FF") : (settings.colorTemperatureAbove  ?: "#FF8C00")
        case "humidity":
            return (rangeSt == "below") ? (settings.colorHumidityBelow    ?: "#8B4513") : (settings.colorHumidityAbove    ?: "#0000FF")
        case "illumination":
            return (rangeSt == "below") ? (settings.colorIlluminationBelow ?: "#000000") : (settings.colorIlluminationAbove ?: "#FFFF00")
        default:
            return (settings.colorNumericInRange ?: "#708090")
    }
}

private void setSlotValueForPage(int page, int idx, String text, rangeState, iconKey) {
    if (idx < 1 || idx > maxSensors(page)) return
    String rs      = (rangeState ?: "within") as String
    boolean ok     = (rs == "within")
    boolean wasOk  = (state[stateKey(page, idx)] ?: "inactive") != "active"

    state[valueTextKey(page, idx)] = text
    state[stateKey(page, idx)]     = ok ? "inactive" : "active"
    state[rangeStateKey(page, idx)] = rs

    String sType = state[typeKey(page, idx)] ?: "temperature"
    String color = numericColorFor(sType, rs)
    if (sType == "weather") {
        String ik = (iconKey ?: "") as String
        state[wxIconStateKey(page, idx)] = ik
        renderWeatherTile(page, idx, weatherIconGlyph(ik), text, color)
    } else {
        String lbl = state[labelKey(page, idx)] ?: ""
        renderTile(page, idx, text, lbl, color)
    }

    if (!ok) {
        if (wasOk) {
            // Transition into alert -- jump to page + backlight, mirroring motion-active
            if (!state.suppressNavigation) {
                unschedule("rotatePage")
                safePub("hasp/${settings.haspNode ?: 'plate'}/command/page", "${page}")
            }
            if (settings.backlightOnMotion) {
                unschedule("backlightOff"); unschedule("motionTimeoutBacklightOff")
                state.screenIdle = false
                publishBacklight(true)
                int secs = (settings.motionBacklightTimeout ?: 60) as int
                if (secs > 0) runIn(secs, "motionTimeoutBacklightOff")
            }
        }
    } else if (wasOk == false) {
        // Transition back to normal
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
        if (allInactive() && !state.suppressNavigation && !state.pushInProgress) {
            maybeRestartRotation()
        }
    }
}

// Font size for the value_str on a numeric/clock tile. iconFontFor() is sized
// for a single 1-2 char icon glyph -- reused here it's too large for longer
// strings like "72.5°" or "3:45 PM" and wraps, colliding with the bottom label.
private int valueFontFor(String grid) {
    switch (grid) {
        case "1x1": return 40
        case "2x1": return 40
        case "2x2": return 32
        case "3x3": return 24
        case "4x4": return 18
        case "5x5": return 12
        case "6x5": return 12
        case "3x2": return 24
        case "4x3": return 18
        case "5x4": return 12
        default:    return 24
    }
}

// Lifts the value text above the tile's true vertical center, leaving clear
// room below for the label (which starts at labelPadTopFor).
private int valueOffsetYFor(String grid) {
    switch (grid) {
        case "1x1": return -60
        case "2x1": return -50
        case "2x2": return -35
        case "3x3": return -22
        case "4x4": return -14
        case "5x5": return  -8
        case "6x5": return -10
        case "3x2": return -30
        case "4x3": return -18
        case "5x4": return -10
        default:    return -22
    }
}

// Device-name labels can wrap to 2 lines (see wrapLabel/maxCharsForGrid in
// the app), so this needs enough room below it for 2 lines at labelFontFor,
// not just 1 -- the previous values left as little as 32px, clipping a
// single line outright. Kept separate from labelPadTopFor (rather than
// reusing it) only so icon+label tiles are unaffected by any future tuning.
private int numericLabelPadTopFor(String grid) {
    switch (grid) {
        case "1x1": return 375
        case "2x1": return 375
        case "2x2": return 150
        case "3x3": return  85
        case "4x4": return  58
        case "5x5": return  56
        case "6x5": return  56
        case "3x2": return 165
        case "4x3": return  98
        case "5x4": return  75
        default:    return  85
    }
}

// Renders a tile with a large centered value (bigText) and a small bottom
// label (smallText) -- shared by numeric value tiles and clock tiles.
// The label lives in its own companion object (see labelObjectJsonl, created
// with fixed x/y at layout time) rather than the btn's own text/pad_top,
// since openHASP doesn't reliably re-apply pad_top via a later patch.
private void renderTile(int page, int idx, String bigText, String smallText, String bgColor) {
    String node        = settings.haspNode ?: "plate"
    String escapedBig   = labelToJsonEscape(bigText ?: "")
    String grid         = activeGrid(page)
    int    vFont        = valueFontFor(grid)
    int    vOfsY        = valueOffsetYFor(grid)
    String tColor       = contrastColor(bgColor)

    String jsonl = '{"page":' + page + ',"id":' + bgId(idx) +
        ',"bg_color":"' + bgColor + '"' +
        ',"value_str":"' + escapedBig + '"' +
        ',"value_font":' + vFont +
        ',"value_ofs_x":0' +
        ',"value_ofs_y":' + vOfsY +
        ',"value_color":"' + tColor + '"' +
        ',"click":' + tapClickableFor(page, idx) + '}'
    safePub("hasp/" + node + "/command/jsonl", jsonl)

    String escapedSmall = labelToJsonEscape(smallText ?: "")
    String lblJsonl = '{"page":' + page + ',"id":' + labelObjId(idx) +
        ',"text":"' + escapedSmall + '"' +
        ',"text_color":"' + tColor + '"}'
    safePub("hasp/" + node + "/command/jsonl", lblJsonl)
}

// Weather icon (condition glyph) sits in the top portion of the tile, sized
// like a real icon (not the tiny valueFontFor used for a single number).
// Used on every grid regardless of full/compact summary mode.
private int weatherIconFontFor(String grid) {
    switch (grid) {
        case "1x1": return 72
        case "2x1": return 56
        case "2x2": return 48
        case "3x3": return 32
        case "4x4": return 22
        case "5x5": return 16
        case "6x5": return 16
        case "3x2": return 40
        case "4x3": return 28
        case "5x4": return 18
        default:    return 32
    }
}

private int weatherIconOffsetYFor(String grid) {
    switch (grid) {
        case "1x1": return -150
        case "2x1": return -160
        case "2x2": return  -70
        case "3x3": return  -46
        case "4x4": return  -30
        case "5x5": return  -18
        case "6x5": return  -22
        case "3x2": return  -70
        case "4x3": return  -46
        case "5x4": return  -26
        default:    return  -46
    }
}

// Full temp/humidity/pressure/wind block (up to 5 lines) -- only reachable
// on "1x1"/"2x1"/"2x2" (4 tiles or fewer, see weatherWantsFullSummary).
// Denser grids show icon + temperature only, sized via labelFontFor/
// labelPadTopFor like any other single-line tile instead of these.
private int weatherDataFontFor(String grid) {
    switch (grid) {
        case "1x1": return 36
        case "2x1": return 24
        case "2x2": return 19
        default:    return 19
    }
}

// Pulled well up from labelPadTopFor's single-line position -- 5 lines at
// weatherDataFontFor need roughly 5 * 1.3 * font pixels plus margin; these
// values leave at least that much room below them so nothing clips.
private int weatherDataPadTopFor(String grid) {
    switch (grid) {
        case "1x1": return 230
        case "2x1": return 310
        case "2x2": return 100
        default:    return 100
    }
}

// Compact temperature (icon + temp only, grids > 4 tiles) -- noticeably
// bigger than a device-name label since it's the tile's only line of text,
// not a caption under an icon.
private int weatherCompactFontFor(String grid) {
    switch (grid) {
        case "3x2": return 32
        case "3x3": return 26
        case "4x3": return 22
        case "4x4": return 18
        case "5x4": return 15
        case "5x5": return 13
        case "6x5": return 13
        default:    return 26
    }
}

// Vertically centers the compact temperature in the space left below the
// icon (weatherIconFontFor/weatherIconOffsetYFor), rather than pinning it
// to the bottom like labelPadTopFor does for a device-name caption.
private int weatherCompactPadTopFor(String grid) {
    switch (grid) {
        case "3x2": return 134
        case "3x3": return  87
        case "4x3": return  89
        case "4x4": return  68
        case "5x4": return  70
        case "5x5": return  58
        case "6x5": return  56
        default:    return  87
    }
}

// Weather shows the full summary only when the page has 4 or fewer tiles
// ("1x1"/"2x1"/"2x2"); denser grids have no room for 5 lines, so they
// collapse to icon + temperature (see formatWeatherSummary in the app).
private boolean weatherWantsFullSummary(String grid) {
    return maxSlotsForGrid(grid) <= 4
}

// Weather has no separate device-name label -- instead it shows a condition
// icon up top and a curated humidity/pressure/wind data block below, in its
// own companion label object (see labelObjectJsonl / renderTile's comment
// for why: pad_top on the btn object isn't reliably re-appliable).
private void renderWeatherTile(int page, int idx, String iconGlyph, String dataText, String bgColor) {
    String node        = settings.haspNode ?: "plate"
    String escapedIcon  = iconToJsonEscape(iconGlyph ?: "")
    String grid         = activeGrid(page)
    int    iFont        = weatherIconFontFor(grid)
    int    iOfsY        = weatherIconOffsetYFor(grid)
    String tColor       = contrastColor(bgColor)

    String jsonl = '{"page":' + page + ',"id":' + bgId(idx) +
        ',"bg_color":"' + bgColor + '"' +
        ',"value_str":"' + escapedIcon + '"' +
        ',"value_font":' + iFont +
        ',"value_ofs_x":0' +
        ',"value_ofs_y":' + iOfsY +
        ',"value_color":"' + tColor + '"' +
        ',"click":' + tapClickableFor(page, idx) + '}'
    safePub("hasp/" + node + "/command/jsonl", jsonl)

    String escapedData = labelToJsonEscape(dataText ?: "")
    String lblJsonl = '{"page":' + page + ',"id":' + labelObjId(idx) +
        ',"text":"' + escapedData + '"' +
        ',"text_color":"' + tColor + '"}'
    safePub("hasp/" + node + "/command/jsonl", lblJsonl)
}

// ── Clock tiles (Mixed-page slots only, no device) ────────────────────────────

// Called by the app at layout-push time to register per-slot clock display
// options. Rendering itself happens on the driver's own per-minute tick.
def updatePage1ClockConfig(config) { applyClockConfig(config, 1) }
def updatePage2ClockConfig(config) { applyClockConfig(config, 2) }
def updatePage3ClockConfig(config) { applyClockConfig(config, 3) }
def updatePage4ClockConfig(config) { applyClockConfig(config, 4) }
def updatePage5ClockConfig(config) { applyClockConfig(config, 5) }
def updatePage6ClockConfig(config) { applyClockConfig(config, 6) }
def updatePage7ClockConfig(config) { applyClockConfig(config, 7) }
def updatePage8ClockConfig(config) { applyClockConfig(config, 8) }
def updatePage9ClockConfig(config) { applyClockConfig(config, 9) }
def updatePage10ClockConfig(config) { applyClockConfig(config, 10) }
def updatePage11ClockConfig(config) { applyClockConfig(config, 11) }
def updatePage12ClockConfig(config) { applyClockConfig(config, 12) }

private void applyClockConfig(config, int page) {
    if (!(config instanceof Map)) {
        try { config = new groovy.json.JsonSlurper().parseText(config.toString()) }
        catch (Exception e) { infoLog "[Dashboard] WARN -- bad clock config JSON: ${e.message}"; return }
    }
    config.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 30) return
        state["p${page}clockShowTime${idx}"] = (v.showTime != false)
        state["p${page}clockShowDate${idx}"] = (v.showDate != false)
        state["p${page}clockFormat${idx}"]   = (v.format ?: "12") as String
    }
    infoLog "[Dashboard] Clock config stored for page ${page}: ${config.size()} slot(s)"
    runIn(2, "clockTick")
}

// Called by the app at layout-push time to register per-slot tap-to-navigate
// targets. Config keys not present in the map are cleared (so disabling
// tap-nav for a slot doesn't leave stale state behind).
def updatePage1TapConfig(config) { applyTapConfig(config, 1) }
def updatePage2TapConfig(config) { applyTapConfig(config, 2) }
def updatePage3TapConfig(config) { applyTapConfig(config, 3) }
def updatePage4TapConfig(config) { applyTapConfig(config, 4) }
def updatePage5TapConfig(config) { applyTapConfig(config, 5) }
def updatePage6TapConfig(config) { applyTapConfig(config, 6) }
def updatePage7TapConfig(config) { applyTapConfig(config, 7) }
def updatePage8TapConfig(config) { applyTapConfig(config, 8) }
def updatePage9TapConfig(config) { applyTapConfig(config, 9) }
def updatePage10TapConfig(config) { applyTapConfig(config, 10) }
def updatePage11TapConfig(config) { applyTapConfig(config, 11) }
def updatePage12TapConfig(config) { applyTapConfig(config, 12) }

private void applyTapConfig(config, int page) {
    if (!(config instanceof Map)) {
        try { config = new groovy.json.JsonSlurper().parseText(config.toString()) }
        catch (Exception e) { infoLog "[Dashboard] WARN -- bad tap config JSON: ${e.message}"; return }
    }
    (1..30).each { slot ->
        state.remove(tapTargetKey(page, slot))
        state.remove(tapRevertKey(page, slot))
    }
    config.each { k, v ->
        int slot = (k as String).toInteger()
        if (slot < 1 || slot > 30) return
        state[tapTargetKey(page, slot)] = (v.targetPage as int)
        state[tapRevertKey(page, slot)] = (v.revertSeconds as int)
    }
    infoLog "[Dashboard] Tap-nav config stored for page ${page}: ${config.size()} slot(s)"
}

// Runs every minute (scheduled in initialize()); re-renders every slot whose
// type is "clock" with the current time/date. No-op if there are none.
def clockTick() {
    int numPg = (state.numberOfPages ?: 12) as int
    (1..numPg).each { pg ->
        int ms = maxSensors(pg)
        if (ms < 1) return
        (1..ms).each { idx ->
            String sType = state[typeKey(pg, idx)] ?: "none"
            if (sType != "clock") return
            boolean showTime = (state["p${pg}clockShowTime${idx}"] != false)
            boolean showDate = (state["p${pg}clockShowDate${idx}"] != false)
            String fmt       = state["p${pg}clockFormat${idx}"] ?: "12"
            renderClockTile(pg, idx, showTime, showDate, fmt)
        }
    }
}

private void renderClockTile(int page, int idx, boolean showTime, boolean showDate, String fmt) {
    Date now = new Date()
    java.util.TimeZone tz = location?.timeZone ?: java.util.TimeZone.getDefault()
    String timeStr = now.format(fmt == "24" ? "HH:mm" : "h:mm a", tz)
    String dateStr = now.format("EEE, MMM d", tz)

    String big, small
    if (showTime && showDate)      { big = timeStr; small = dateStr }
    else if (showTime)             { big = timeStr; small = "" }
    else if (showDate)             { big = dateStr; small = "" }
    else                           { big = timeStr; small = "" }   // fallback -- always show something

    renderTile(page, idx, big, small, settings.colorClockBackground ?: "#000000")
}

// ── Backlight ──────────────────────────────────────────────────────────────────

private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule("backlightOff")
    if (!allInactive()) {
        int secs = (settings.motionBacklightTimeout != null ? settings.motionBacklightTimeout : 60) as int
        if (secs > 0) runIn(secs, "motionTimeoutBacklightOff")
    } else {
        int delay = (settings.touchBacklightTimeout != null ? settings.touchBacklightTimeout : 30) as int
        if (delay > 0) runIn(delay, "backlightOff")
    }
}

private void pushIdleConfig() {
    // The display idle timer durations are set in the display's web UI (Display Settings).
    // We can't set them via MQTT. Instead we use our own Hubitat-side timer.
    scheduleIdleTimeout()
    String node = settings.haspNode ?: "plate"
    infoLog "[Dashboard] Idle config: Hubitat-side timer active (${settings.idleTimeout ?: 0} sec)"
}

private void scheduleIdleTimeout() {
    unschedule("idleTimeoutBacklightOff")
    int secs = (settings.idleTimeout != null ? settings.idleTimeout : 0) as int
    if (secs > 0) {
        runIn(secs, "idleTimeoutBacklightOff")
        debugLog "[Dashboard] Idle timer set: ${secs}s"
    }
}

def idleTimeoutBacklightOff() {
    infoLog "[Dashboard] Idle timeout -- blanking display"
    publishBacklight(false)
    state.screenIdle = true
}

def backlightOff() {
    publishBacklight(false)
    state.screenIdle = true
}

def backlightOnAfterFade() {
    if (!settings.backlightOnMotion) return
    if (!allInactive()) return
    if (state.screenIdle) return
    // Fade just completed -- reset idle timer so blank waits the full interval from now
    scheduleIdleTimeout()
    int delay = (settings.backlightOffDelay ?: 0) as int
    if (delay > 0) runIn(delay, "backlightOff")
}

def motionTimeoutBacklightOff() {
    if (!settings.backlightOnMotion) return
    backlightOff()
}

private boolean allInactive() {
    int numPg = (state.numberOfPages ?: 12) as int
    (1..numPg).every { pg ->
        int ms = maxSensors(pg)
        if (ms < 1) return true
        (1..ms).every { idx ->
            String sType = state[typeKey(pg, idx)] ?: "none"
            if (sType == "light" || sType == "lock" || sType == "garage" || sType == "clock") return true
            return state[stateKey(pg, idx)] != "active"
        }
    }
}

private void publishBacklight(boolean on) {
    String node = settings.haspNode ?: "plate"
    safePub("hasp/${node}/command/backlight", on ? '{"state":"on","brightness":255}' : '{"state":"off"}')
    if (on && !state.syncInProgress) scheduleIdleTimeout()
    else if (!on) unschedule("idleTimeoutBacklightOff")
}

// ── Resync ─────────────────────────────────────────────────────────────────────

def resyncStates() {
    if (state.pushInProgress) { infoLog "[Dashboard] Skipping resync -- layout push in progress"; return }
    infoLog "[Dashboard] Resyncing all page states from cache"
    int numPg = (state.numberOfPages ?: 12) as int
    (1..numPg).each { pg ->
        String grid = activeGrid(pg)
        if (grid == "thermostat") {
            // Re-push thermostat tile from stored state including away flag
            String nd   = settings.haspNode ?: "plate"
            String temp = (state["p${pg}thermostatTemp"]    ?: "--") as String
            String heat = (state["p${pg}thermostatHeat"]    ?: "--") as String
            String cool = (state["p${pg}thermostatCool"]    ?: "--") as String
            String mode = (state["p${pg}thermostatMode"]    ?: "off") as String
            String ops  = (state["p${pg}thermostatOpState"] ?: "idle") as String
            boolean away = (state["p${pg}thermostatAway"] == true)
            if (temp == "--") {
                sendEvent(name: "layoutPushComplete", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            } else {
                pushThermostatTile(pg, nd, temp, heat, cool, mode, ops, away)
            }
            return
        }
        int ms = maxSensors(pg)
        if (ms < 1) return
        (1..ms).each { idx ->
            String sType = state[typeKey(pg, idx)] ?: "none"
            String st    = state[stateKey(pg, idx)] ?: "inactive"
            if (sType == "none" || st == "empty")  { setSlotEmptyForPage(pg, idx) }
            else if (sType == "clock") {
                boolean showTime = (state["p${pg}clockShowTime${idx}"] != false)
                boolean showDate = (state["p${pg}clockShowDate${idx}"] != false)
                String fmt       = state["p${pg}clockFormat${idx}"] ?: "12"
                renderClockTile(pg, idx, showTime, showDate, fmt)
            }
            else if (isNumericType(sType)) {
                String text  = state[valueTextKey(pg, idx)] ?: "--"
                String rs    = state[rangeStateKey(pg, idx)] ?: "within"
                String color = numericColorFor(sType, rs)
                if (sType == "weather") {
                    String ik = state[wxIconStateKey(pg, idx)] ?: ""
                    renderWeatherTile(pg, idx, weatherIconGlyph(ik), text, color)
                } else {
                    renderTile(pg, idx, text, state[labelKey(pg, idx)] ?: "", color)
                }
            }
            else if (st == "active")               { setMotionActiveForPage(pg, idx) }
            else                                    { setMotionInactiveForPage(pg, idx) }
            pauseExecution(30)
        }
    }
}

def fireDisplayRebooted() {
    state.lwtPending = false
    sendEvent(name: "displayRebooted", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// ── Layout push ────────────────────────────────────────────────────────────────

def pushAllLayouts(numberOfPages) {
    int np = Math.min(12, Math.max(1, (numberOfPages as int)))
    // Don't push into a dead connection -- wait for reconnect then LWT will retrigger.
    // Use interfaces.mqtt.isConnected() not the device attribute -- the attribute
    // persists across Hubitat restarts and causes false "Connected" on startup.
    boolean mqttUp = false
    try { mqttUp = interfaces.mqtt.isConnected() } catch (Exception e) { mqttUp = false }
    if (!mqttUp) {
        infoLog "[Dashboard] pushAllLayouts deferred -- MQTT not connected"
        state.deferredPages = np
        return
    }
    state.numberOfPages      = np
    state.deferredPages      = null
    state.lastPushMs         = now()
    state.pushInProgress     = true
    state.suppressNavigation = true
    state.rotationPage       = 1
    state.deferClearPage     = 0   // reset any leftover defer from previous push
    sendEvent(name: "pushInProgress", value: "true")
    unschedule("rotatePage")
    unschedule("returnToPage1AndStartRotation")
    infoLog "[Dashboard] pushAllLayouts -- ${np} page(s)"
    sendEvent(name: "mqttStatus", value: "Building layouts...")
    String node = settings.haspNode ?: "plate"
    // Clear all pages at once before building. This prevents stale objects from
    // a previous push (e.g. a motion icon on the old page 2) bleeding through
    // into the new layout. The display briefly shows blank while page 1 builds.
    safePub("hasp/${node}/command", "clearpage all")
    pauseExecution(400)
    runIn(2, "pushPage1Layout")
}

def pushPage1Layout() {
    publishBacklight(true)
    pushPageLayout(1)
}

def pushPage2Layout() {
    int np2 = (state.numberOfPages ?: 12) as int
    if (np2 >= 2) pushPageLayout(2)
}

def pushPage3Layout() {
    int np3 = (state.numberOfPages ?: 12) as int
    if (np3 >= 3) pushPageLayout(3)
}

def pushPage4Layout() {
    int np4 = (state.numberOfPages ?: 12) as int
    if (np4 >= 4) pushPageLayout(4)
}

def pushPage5Layout() {
    int np5 = (state.numberOfPages ?: 12) as int
    if (np5 >= 5) pushPageLayout(5)
}

def pushPage6Layout() {
    int np6 = (state.numberOfPages ?: 12) as int
    if (np6 >= 6) pushPageLayout(6)
}

def pushPage7Layout() {
    int np7 = (state.numberOfPages ?: 12) as int
    if (np7 >= 7) pushPageLayout(7)
}

def pushPage8Layout() {
    int np8 = (state.numberOfPages ?: 12) as int
    if (np8 >= 8) pushPageLayout(8)
}

def pushPage9Layout() {
    int np9 = (state.numberOfPages ?: 12) as int
    if (np9 >= 9) pushPageLayout(9)
}

def pushPage10Layout() {
    int np10 = (state.numberOfPages ?: 12) as int
    if (np10 >= 10) pushPageLayout(10)
}

def pushPage11Layout() {
    int np11 = (state.numberOfPages ?: 12) as int
    if (np11 >= 11) pushPageLayout(11)
}

def pushPage12Layout() {
    int np12 = (state.numberOfPages ?: 12) as int
    if (np12 >= 12) pushPageLayout(12)
}

private void pushPageLayout(int page) {
    String grid     = activeGrid(page)
    int total       = (state.numberOfPages ?: 12) as int
    String node     = settings.haspNode ?: "plate"
    int slots       = maxSensors(page)

    infoLog "[Dashboard] Pushing page ${page}/${total}: grid='${grid}' slots=${slots} storedGrid='${state["page${page}GridLayout"]}' storedSlots='${state["page${page}MaxSlots"]}'"
    sendEvent(name: "mqttStatus", value: "Pushing page ${page}/${total}...")

    // Clear the page before building it. Since we navigated to page 0 at push
    // start, no content page is currently visible, so no flash occurs.
    safePub("hasp/${node}/command", "clearpage ${page}")
    pauseExecution(150)

    if (slots > 0) {
        (1..slots).each { s ->
            unschedule("p${page}fadeStep${s}")
            state.remove("p${page}fadeStep${s}")
        }
    }

    // 1. Layout JSONL -- tile structure
    layoutJsonl(grid, page, total).each { jsonl ->
        safePub("hasp/${node}/command/jsonl", jsonl)
        pauseExecution(40)
    }

    pauseExecution(150)

    // 2. Slot colors and icons
    if (grid == "thermostat") {
        // Button labels are set by updateThermostatDisplay after app syncs
        // Set placeholder text on control buttons
        String nd = settings.haspNode ?: "plate"
        // Let skeleton settle then push colors and content immediately
        pauseExecution(500)
        String temp    = (state["p${page}thermostatTemp"]    ?: "--") as String
        String heat    = (state["p${page}thermostatHeat"]    ?: "--") as String
        String cool    = (state["p${page}thermostatCool"]    ?: "--") as String
        String tMode   = (state["p${page}thermostatMode"]    ?: "off") as String
        String tOps    = (state["p${page}thermostatOpState"] ?: "idle") as String
        boolean tAway  = (state["p${page}thermostatAway"] == true)
        if (temp != "--") {
            pushThermostatTile(page, nd, temp, heat, cool, tMode, tOps, tAway)
        } else {
            // No stored data yet -- send placeholder labels
            [[2,""], [3,"Mode"], [4,""]].each { entry ->
                int sid = entry[0] as int
                String lbl = iconToJsonEscape(entry[1] as String)
                safePub("hasp/" + nd + "/command/jsonl", '{"page":' + page + ',"id":' + sid + ',"text":"' + lbl + '"}')
                pauseExecution(20)
            }
        }
        int drainSecs2 = 3
        runIn(drainSecs2, "navigatePage${page}")
        return
    }

    // Icons sent via JSONL to preserve non-ASCII (raw topic strips Unicode private-use chars).
    // Labels come from syncAllSensors after push -- no need to duplicate here.
    if (slots > 0) {
        (1..slots).each { idx ->
            try {
                String slotType = state[typeKey(page, idx)] ?: "none"

                if (!slotType || slotType == "none") {
                    // Empty slot -- grey, no icon, no label
                    String emptyJsonl = '{"page":' + page + ',"id":' + bgId(idx) + ',"bg_color":"#708090","text_color":"#FFFFFF","text":"","click":false}'
                    safePub("hasp/${node}/command/jsonl", emptyJsonl)
                } else if (slotType == "light" || slotType == "lock" || slotType == "garage") {
                    // Tappable tile -- buildLightTileJsonl handles icon+label+color+click atomically
                    String activeState = state[stateKey(page, idx)] ?: "inactive"
                    String activeColor = (slotType == "light")  ? (settings.colorLightActive ?: "#FFFF00") :
                                         (slotType == "lock")   ? (settings.colorLockOpen    ?: "#E65100") :
                                                                  (settings.colorGarageOpen  ?: "#E65100")
                    String tileColor   = (activeState == "active") ? activeColor : inactiveColorFor(page, idx)
                    String lbl         = state[labelKey(page, idx)] ?: ""
                    String clickJsonl  = buildLightTileJsonl(page, idx, grid, tileColor, lbl)
                    safePub("hasp/${node}/command/jsonl", clickJsonl)
                } else if (isNumericType(slotType)) {
                    // Numeric value tile -- placeholder until the app pushes a real
                    // reading via setPageXSlotValue (avoids showing a stale icon).
                    String text  = state[valueTextKey(page, idx)] ?: "--"
                    String rs    = state[rangeStateKey(page, idx)] ?: "within"
                    String color = numericColorFor(slotType, rs)
                    if (slotType == "weather") {
                        String ik = state[wxIconStateKey(page, idx)] ?: ""
                        renderWeatherTile(page, idx, weatherIconGlyph(ik), text, color)
                    } else {
                        renderTile(page, idx, text, state[labelKey(page, idx)] ?: "", color)
                    }
                } else if (slotType == "clock") {
                    // Clock tile -- render immediately so it doesn't wait for the next tick
                    boolean showTime = (state["p${page}clockShowTime${idx}"] != false)
                    boolean showDate = (state["p${page}clockShowDate${idx}"] != false)
                    String fmt       = state["p${page}clockFormat${idx}"] ?: "12"
                    renderClockTile(page, idx, showTime, showDate, fmt)
                } else {
                    // Non-tappable sensor tile -- publishIconJsonl handles icon+label+color
                    String ic  = inactiveColorFor(page, idx)
                    String lbl = state[labelKey(page, idx)] ?: ""
                    // Store label in state first so publishIconJsonl can embed it
                    if (lbl) state[labelKey(page, idx)] = lbl
                    publishIconJsonl(node, page, idx, inactiveIconFor(page, idx), ic)
                }
            } catch (Exception e) {
                infoLog "[Dashboard] WARN -- slot ${idx} render failed: ${e.message}"
            }
            pauseExecution(20)
        }
    }

    int drainSecs = 3 + (int)(slots * 0.1)
    infoLog "[Dashboard] Scheduling navigate to page ${page}/${total} in ${drainSecs}s"
    runIn(drainSecs, "navigatePage${page}")
}

def navigatePage1() {
    doNavigate(1)
}

def navigatePage2() {
    doNavigate(2)
}

def navigatePage3() {
    doNavigate(3)
}

def navigatePage4() {
    doNavigate(4)
}

def navigatePage5() {
    doNavigate(5)
}

def navigatePage6() {
    doNavigate(6)
}

def navigatePage7() {
    doNavigate(7)
}

def navigatePage8() {
    doNavigate(8)
}

def navigatePage9() {
    doNavigate(9)
}

def navigatePage10() {
    doNavigate(10)
}

def navigatePage11() {
    doNavigate(11)
}

def navigatePage12() {
    doNavigate(12)
}

private void doNavigate(int page) {
    int total   = (state.numberOfPages ?: 12) as int
    String node = settings.haspNode ?: "plate"
    infoLog "[Dashboard] Navigating to page ${page}/${total}"

    // Navigate to this page so the user sees it
    safePub("hasp/${node}/command/page", "${page}")
    state.currentDisplayPage = page

    if (page < total) {
        // Build the next page while the user is viewing this one
        infoLog "[Dashboard] Scheduling next page build after page ${page}"
        runIn(3, "pushPage${page + 1}Layout")
    } else {
        // Last page is now visible -- stay here a moment then return to page 1.
        // Keep pushInProgress=true until returnToPage1AndStartRotation completes
        // so statusupdate-triggered resyncStates cannot race the push.
        infoLog "[Dashboard] All ${total} page(s) pushed"
        runIn(12, "returnToPage1AndStartRotation")
    }
}

def returnToPage1AndStartRotation() {
    String node  = settings.haspNode ?: "plate"
    int numPg    = (state.numberOfPages ?: 1) as int
    // Re-push any thermostat tiles from stored state -- clearpage wiped them during layout build
    (1..numPg).each { pg ->
        if (activeGrid(pg) == "thermostat") {
            String temp = (state["p${pg}thermostatTemp"]    ?: "--") as String
            String heat = (state["p${pg}thermostatHeat"]    ?: "--") as String
            String cool = (state["p${pg}thermostatCool"]    ?: "--") as String
            String mode = (state["p${pg}thermostatMode"]    ?: "off") as String
            String ops  = (state["p${pg}thermostatOpState"] ?: "idle") as String
            pushThermostatTile(pg, node, temp, heat, cool, mode, ops)
            pauseExecution(50)
        }
    }
    safePub("hasp/${node}/command/page", "1")
    state.currentDisplayPage = 1
    state.lastPushMs     = now()   // extend grace window past syncAllSensors
    state.pushInProgress = false
    state.syncInProgress = true    // block idle timer resets during syncAllSensors
    scheduleIdleTimeout()
    sendEvent(name: "pushInProgress",     value: "false")
    sendEvent(name: "mqttStatus",         value: "Connected")
    sendEvent(name: "layoutPushComplete", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    infoLog "[Dashboard] Layout push complete -- syncAllSensors will follow"
    // Start rotation after sync has had time to complete (~15s)
    // suppressNavigation cleared in startRotationAfterSync so sensor events don't race
    int total = (state.numberOfPages ?: 1) as int
    if (total > 1) {
        runIn(15, "startRotationAfterSync")
    } else {
        state.suppressNavigation = false
    }
}

def startRotationAfterSync() {
    state.suppressNavigation = false
    state.syncInProgress     = false   // sync window over -- idle timer resets now permitted
    int total  = (state.numberOfPages ?: 1) as int
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (total > 1 && rotInt > 0) {
        state.rotationPage = 1
        unschedule("rotatePage")
        runIn(rotInt, "rotatePage")
        infoLog "[Dashboard] Rotation started -- every ${rotInt}s"
    }
}

// Restart page rotation if it was stopped by a sensor event and all sensors are now inactive.
private void maybeRestartRotation() {
    int total  = (state.numberOfPages ?: 1) as int
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (total < 2 || rotInt < 1) return
    unschedule("rotatePage")
    runIn(rotInt, "rotatePage")
    debugLog "[Dashboard] Rotation restarted after all sensors inactive"
}

// ── Layout JSONL generators ────────────────────────────────────────────────────

// Returns [yOffsetWithinCell, fontSize] for a slot's companion label object,
// based on its configured type -- weather needs a much bigger, earlier-
// starting region (5 lines) than a single-line device-name label.
private List labelGeometryFor(String grid, String sType) {
    if (sType == "weather") {
        if (weatherWantsFullSummary(grid)) {
            return [weatherDataPadTopFor(grid), weatherDataFontFor(grid)]
        }
        return [weatherCompactPadTopFor(grid), weatherCompactFontFor(grid)]
    } else if (isNumericType(sType) || sType == "clock") {
        return [numericLabelPadTopFor(grid), labelFontFor(grid, "")]
    } else {
        return [labelPadTopFor(grid), labelFontFor(grid, "")]
    }
}

// Companion "label" object for a cell, created alongside its "btn" object.
// Positioned with explicit x/y at creation time (always honored) rather
// than relying on the "btn" object's pad_top (not reliably re-appliable
// via a later patch). Only renderTile/renderWeatherTile ever populate its
// text; icon+label tiles (motion/smoke/etc) keep using the btn's own text.
private String labelObjectJsonl(int page, int slot, int x, int y, int w, int h, String grid) {
    String sType = state[typeKey(page, slot)] ?: "none"
    List geom = labelGeometryFor(grid, sType)
    int offsetY = geom[0] as int
    int font    = geom[1] as int
    int labelY  = y + offsetY
    int labelH  = Math.max(10, (y + h) - labelY - 4)
    return """{"page":${page},"id":${labelObjId(slot)},"obj":"label","x":${x + 2},"y":${labelY},"w":${w - 4},"h":${labelH},"bg_opa":0,"border_width":0,"text":"","text_font":${font},"align":"center","text_color":"white","click":false}"""
}

private List<String> layoutJsonl(String grid, int page, int totalPages) {
    List<String> out
    switch (grid) {
        case "thermostat": out = layoutThermostat(page); break
        case "1x1": out = layout1x1(page); break
        case "2x1": out = layoutGrid(page, 2, 1, 236, 476, 2, 28, 40, 380, 4, 10, "2x1"); break
        case "3x3": out = layout3x3(page); break
        case "4x4": out = layout4x4(page); break
        case "5x5": out = layoutNxN(page, 5, 94, 2, 12, 16); break
        case "6x5": out = layout6x5(page); break
        case "3x2": out = layoutGrid(page, 3, 2, 157, 237, 2, 24, 32, 180, 4, 10, "3x2"); break
        case "4x3": out = layoutGrid(page, 4, 3, 117, 157, 2, 20, 24, 105, 2, 8, "4x3"); break
        case "5x4": out = layoutGrid(page, 5, 4,  93, 117, 2, 14, 16,  85, 1, 4, "5x4"); break
        default:    out = layout2x2(page)
    }

    if (totalPages > 1) {
        int prevPage = (page == 1) ? totalPages : page - 1
        int nextPage = (page == totalPages) ? 1 : page + 1
        int navOpa = 20
        out << """{"page":${page},"id":201,"obj":"btn","x":0,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":${navOpa},"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${prevPage}"}"""
        out << """{"page":${page},"id":202,"obj":"btn","x":450,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":${navOpa},"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${nextPage}"}"""
        if (settings.showPageIndicator == true) {
            out << """{"page":${page},"id":200,"obj":"label","x":432,"y":4,"w":46,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"${page}/${totalPages}","text_font":16,"text_color":"white","align":"center","click":false}"""
        } else {
            out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        }
    } else {
        if (settings.showPageIndicator == true) {
            out << """{"page":${page},"id":200,"obj":"label","x":432,"y":4,"w":46,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"1/1","text_font":16,"text_color":"white","align":"center","click":false}"""
        } else {
            out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        }
        out << """{"page":${page},"id":201,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":202,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
    }
    return out
}

// Fixed 2x2 thermostat layout: slot1=display, 2=+, 3=-, 4=mode
private List<String> layoutThermostat(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        int sid = r[0]; int x = r[1]; int y = r[2]; int w = r[3]; int h = r[4]
        int tf = (sid == 1) ? 32 : 56
        boolean clickable = (sid > 1)
        out << """{"page":${page},"id":${sid},"obj":"btn","x":${x},"y":${y},"w":${w},"h":${h},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":${tf},"align":"center","text_color":"white","toggle":false,"click":${clickable}}"""
    }
    return out
}

// pad_top here must match labelPadTopFor(grid) -- openHASP doesn't reliably
// re-apply a pad_top change sent via a later property-only patch, only at
// object creation, so this baked-in value is what actually governs label
// position regardless of what publishIconJsonl/renderTile send afterward.
private List<String> layout1x1(int page) {
    List<String> out = []
    out << """{"page":${page},"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":28,"align":"center","pad_top":380,"text_color":"white","value_str":"","value_font":48,"toggle":false,"click":${tapClickableFor(page, 1)}}"""
    out << labelObjectJsonl(page, 1, 2, 2, 476, 476, "1x1")
    return out
}

private List<String> layout2x2(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"btn","x":${r[1]},"y":${r[2]},"w":${r[3]},"h":${r[4]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":28,"align":"center","pad_top":160,"text_color":"white","value_str":"","value_font":40,"toggle":false,"click":${tapClickableFor(page, r[0] as int)}}"""
        out << labelObjectJsonl(page, r[0] as int, r[1] as int, r[2] as int, r[3] as int, r[4] as int, "2x2")
    }
    return out
}

private List<String> layout3x3(int page) {
    List<String> out = []
    int[][] cells = [[2,2,157,157],[161,2,157,157],[320,2,158,157],[2,161,157,157],[161,161,157,157],[320,161,158,157],[2,320,157,158],[161,320,157,158],[320,320,158,158]]
    cells.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+1},"obj":"btn","x":${c[0]},"y":${c[1]},"w":${c[2]},"h":${c[3]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","pad_top":105,"text_color":"white","value_str":"","value_font":32,"toggle":false,"click":${tapClickableFor(page, i + 1)}}"""
        out << labelObjectJsonl(page, i + 1, c[0], c[1], c[2], c[3], "3x3")
    }
    return out
}

private List<String> layout4x4(int page) {
    List<String> out = []; int cols = 4; int w = 117; int gap = 2
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":2,"radius":8,"text":"","text_font":20,"align":"center","pad_top":78,"text_color":"white","value_str":"","value_font":24,"toggle":false,"click":${tapClickableFor(page, id)}}"""
        out << labelObjectJsonl(page, id, x, y, tw, th, "4x4")
    }}
    return out
}

private List<String> layoutNxN(int page, int cols, int w, int gap, int tf, int iconFont) {
    List<String> out = []
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":${tf},"align":"center","pad_top":60,"text_color":"white","value_str":"","value_font":${iconFont},"toggle":false,"click":${tapClickableFor(page, id)}}"""
        out << labelObjectJsonl(page, id, x, y, tw, th, "5x5")
    }}
    return out
}

private List<String> layoutGrid(int page, int cols, int rows, int colW, int rowH, int gap, int tf, int iconFont, int padTop, int borderWidth, int radius, String grid) {
    List<String> out = []
    (0..<rows).each { row -> (0..<cols).each { col ->
        int id = row * cols + col + 1
        int x  = col * (colW + gap) + gap
        int y  = row * (rowH + gap) + gap
        int tw = (col == cols - 1) ? (480 - x - gap) : colW
        int th = (row == rows - 1) ? (480 - y - gap) : rowH
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":${borderWidth},"radius":${radius},"text":"","text_font":${tf},"align":"center","pad_top":${padTop},"text_color":"white","value_str":"","value_font":${iconFont},"toggle":false,"click":${tapClickableFor(page, id)}}"""
        out << labelObjectJsonl(page, id, x, y, tw, th, grid)
    }}
    return out
}

// 6 columns x 5 rows = 30 slots on a 480x480 display
private List<String> layout6x5(int page) {
    List<String> out = []
    int cols = 6; int rows = 5; int gap = 2
    int colW = 77; int rowH = 94
    (0..<rows).each { row -> (0..<cols).each { col ->
        int id  = row * cols + col + 1
        int x   = col * (colW + gap) + gap
        int y   = row * (rowH + gap) + gap
        int tw  = (col == cols - 1) ? (480 - x - gap) : colW
        int th  = (row == rows - 1) ? (480 - y - gap) : rowH
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","pad_top":58,"text_color":"white","value_str":"","value_font":16,"toggle":false,"click":${tapClickableFor(page, id)}}"""
        out << labelObjectJsonl(page, id, x, y, tw, th, "6x5")
    }}
    return out
}

// ── Page slot state commands ───────────────────────────────────────────────────

def setPage1MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(1)) setMotionActiveForPage(1, i)
}

def setPage1MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(1)) setMotionInactiveForPage(1, i)
}

def setPage1SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(1)) setSlotEmptyForPage(1, i)
}

def setPage2MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(2)) setMotionActiveForPage(2, i)
}

def setPage2MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(2)) setMotionInactiveForPage(2, i)
}

def setPage2SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(2)) setSlotEmptyForPage(2, i)
}

def setPage3MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(3)) setMotionActiveForPage(3, i)
}

def setPage3MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(3)) setMotionInactiveForPage(3, i)
}

def setPage3SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(3)) setSlotEmptyForPage(3, i)
}

def setPage4MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(4)) setMotionActiveForPage(4, i)
}

def setPage4MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(4)) setMotionInactiveForPage(4, i)
}

def setPage4SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(4)) setSlotEmptyForPage(4, i)
}

def setPage5MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(5)) setMotionActiveForPage(5, i)
}

def setPage5MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(5)) setMotionInactiveForPage(5, i)
}

def setPage5SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(5)) setSlotEmptyForPage(5, i)
}

def setPage6MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(6)) setMotionActiveForPage(6, i)
}

def setPage6MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(6)) setMotionInactiveForPage(6, i)
}

def setPage6SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(6)) setSlotEmptyForPage(6, i)
}

def setPage7MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(7)) setMotionActiveForPage(7, i)
}

def setPage7MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(7)) setMotionInactiveForPage(7, i)
}

def setPage7SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(7)) setSlotEmptyForPage(7, i)
}

def setPage8MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(8)) setMotionActiveForPage(8, i)
}

def setPage8MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(8)) setMotionInactiveForPage(8, i)
}

def setPage8SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(8)) setSlotEmptyForPage(8, i)
}

def setPage9MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(9)) setMotionActiveForPage(9, i)
}

def setPage9MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(9)) setMotionInactiveForPage(9, i)
}

def setPage9SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(9)) setSlotEmptyForPage(9, i)
}

def setPage10MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(10)) setMotionActiveForPage(10, i)
}

def setPage10MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(10)) setMotionInactiveForPage(10, i)
}

def setPage10SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(10)) setSlotEmptyForPage(10, i)
}

def setPage11MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(11)) setMotionActiveForPage(11, i)
}

def setPage11MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(11)) setMotionInactiveForPage(11, i)
}

def setPage11SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(11)) setSlotEmptyForPage(11, i)
}

def setPage12MotionActive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(12)) setMotionActiveForPage(12, i)
}

def setPage12MotionInactive(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(12)) setMotionInactiveForPage(12, i)
}

def setPage12SlotEmpty(n) {
    int i = n as int
    if (i >= 1 && i <= maxSensors(12)) setSlotEmptyForPage(12, i)
}


private void setMotionActiveForPage(int page, int idx) {
    state[stateKey(page, idx)] = "active"
    unschedule("p${page}fadeStep${idx}")
    state.remove("p${page}fadeStep${idx}")
    unschedule("backlightOnAfterFade")

    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    debugLog "[Dashboard] Active p${page}s${idx} type=${sType}"

    // Slot has no assigned device -- should never go active, treat as empty
    if (!sType || sType == "none") {
        setSlotEmptyForPage(page, idx)
        return
    }
    if (sType == "light" || sType == "lock" || sType == "garage") {
        String activeColor = (sType == "light")  ? (settings.colorLightActive  ?: "#FFFF00") :
                             (sType == "lock")   ? (settings.colorLockOpen     ?: "#E65100") :
                                                   (settings.colorGarageOpen   ?: "#E65100")
        if (sType == "garage") activeColor = (settings.colorGarageOpen ?: "#E65100")
        String grid       = activeGrid(page)
        String lbl        = state[labelKey(page, idx)] ?: ""
        String clickJsonl = buildLightTileJsonl(page, idx, grid, activeColor, lbl)
        if (clickJsonl) {
            String node = settings.haspNode ?: "plate"
            safePub("hasp/${node}/command/jsonl", clickJsonl)
        }
        return
    }
    // Numeric/clock slots are driven by setSlotValueForPage / clockTick -- not icons
    if (isNumericType(sType) || sType == "clock") return

    String ac = settings.colorActive ?: "#FF0000"
    publishIconJsonl(settings.haspNode ?: "plate", page, idx, activeIconFor(page, idx), ac)

    if (!state.suppressNavigation) {
        // Only stop rotation and jump to page when sensor goes active mid-session
        unschedule("rotatePage")
        String node = settings.haspNode ?: "plate"
        safePub("hasp/${node}/command/page", "${page}")
    }
    // If suppressNavigation is true we are in post-push sync -- don't kill the rotation timer

    if (settings.backlightOnMotion) {
        unschedule("backlightOff")
        unschedule("motionTimeoutBacklightOff")
        state.screenIdle = false
        publishBacklight(true)
        int secs = (settings.motionBacklightTimeout ?: 60) as int
        if (secs > 0) runIn(secs, "motionTimeoutBacklightOff")
    }
}

private void setMotionInactiveForPage(int page, int idx) {
    String fadeKey  = "p${page}fadeStep${idx}"
    boolean wasActive = (state[stateKey(page, idx)] == "active")
    state[stateKey(page, idx)] = "inactive"

    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    debugLog "[Dashboard] Inactive p${page}s${idx} type=${sType} wasActive=${wasActive}"

    // Slot has no assigned device -- treat as empty regardless of how we got here
    if (!sType || sType == "none") {
        setSlotEmptyForPage(page, idx)
        return
    }
    if (sType == "light" || sType == "lock" || sType == "garage") {
        unschedule(fadeKey); state.remove(fadeKey)
        String ic         = inactiveColorFor(page, idx)
        String grid       = activeGrid(page)
        String lbl        = state[labelKey(page, idx)] ?: ""
        String clickJsonl = buildLightTileJsonl(page, idx, grid, ic, lbl)
        if (clickJsonl) {
            String node = settings.haspNode ?: "plate"
            safePub("hasp/${node}/command/jsonl", clickJsonl)
        }
        return
    }
    // Numeric/clock slots are driven by setSlotValueForPage / clockTick -- not icons
    if (isNumericType(sType) || sType == "clock") { unschedule(fadeKey); state.remove(fadeKey); return }

    String sTypeForFade = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (wasActive) {
        unschedule(fadeKey); state[fadeKey] = 0
        // Update icon to inactive (person standing still) but keep red color -- fade handles color
        String node = settings.haspNode ?: "plate"
        String escaped = iconToJsonEscape(inactiveIconFor(page, idx))
        String lbl = state[labelKey(page, idx)] ?: ""
        String escapedLbl = labelToJsonEscape(lbl)
        String grid = activeGrid(page)
        int iFont = iconFontFor(grid); int lFont = labelFontFor(grid, lbl)
        int[] ofs = iconOffsetsFor(grid); int padTop = labelPadTopFor(grid)
        safePub("hasp/${node}/command/jsonl",
            '{"page":' + page + ',"id":' + bgId(idx) +
            ',"value_str":"' + escaped + '","value_font":' + iFont +
            ',"value_ofs_x":' + ofs[0] + ',"value_ofs_y":' + ofs[1] +
            ',"text":"' + escapedLbl + '","text_font":' + lFont +
            ',"pad_top":' + padTop + '}')
        scheduleFadeStep(page, idx)
        if (settings.backlightOnMotion) {
            unschedule("motionTimeoutBacklightOff")
            if (!allInactive()) {
                int secs = (settings.motionBacklightTimeout ?: 60) as int
                if (secs > 0) runIn(secs, "motionTimeoutBacklightOff")
            } else {
                runIn((FADE_STEPS + 1) * fadeInterval() + 2, "backlightOnAfterFade")
            }
        }
    } else {
        String ic = inactiveColorFor(page, idx)
        publishIconJsonl(settings.haspNode ?: "plate", page, idx, inactiveIconFor(page, idx), ic)
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
    }
    // If all sensors are now inactive and rotation was stopped, restart it
    if (allInactive() && !state.suppressNavigation && !state.pushInProgress) {
        maybeRestartRotation()
    }
}

private void setSlotEmptyForPage(int page, int idx) {
    String fadeKey = "p${page}fadeStep${idx}"
    state[stateKey(page, idx)] = "empty"
    unschedule(fadeKey); state.remove(fadeKey)
    String node = settings.haspNode ?: "plate"
    String emptyJsonl = '{"page":' + page + ',"id":' + bgId(idx) + ',"bg_color":"#708090","text_color":"#FFFFFF","text":"","value_str":"","click":false}'
    safePub("hasp/${node}/command/jsonl", emptyJsonl)
    safePub("hasp/${node}/command/jsonl", '{"page":' + page + ',"id":' + labelObjId(idx) + ',"text":""}')
}

// ── Label / type updates ───────────────────────────────────────────────────────

def updatePage1Labels(labels) {
    applyLabels(labels, 1)
}

def updatePage2Labels(labels) {
    applyLabels(labels, 2)
}

def updatePage3Labels(labels) {
    applyLabels(labels, 3)
}

def updatePage4Labels(labels) {
    applyLabels(labels, 4)
}

def updatePage5Labels(labels) {
    applyLabels(labels, 5)
}

def updatePage6Labels(labels) {
    applyLabels(labels, 6)
}

def updatePage7Labels(labels) {
    applyLabels(labels, 7)
}

def updatePage8Labels(labels) {
    applyLabels(labels, 8)
}

def updatePage9Labels(labels) {
    applyLabels(labels, 9)
}

def updatePage10Labels(labels) {
    applyLabels(labels, 10)
}

def updatePage11Labels(labels) {
    applyLabels(labels, 11)
}

def updatePage12Labels(labels) {
    applyLabels(labels, 12)
}

def updatePage1SlotTypes(types) {
    applySlotTypes(types, 1)
}

def updatePage2SlotTypes(types) {
    applySlotTypes(types, 2)
}

def updatePage3SlotTypes(types) {
    applySlotTypes(types, 3)
}

def updatePage4SlotTypes(types) {
    applySlotTypes(types, 4)
}

def updatePage5SlotTypes(types) {
    applySlotTypes(types, 5)
}

def updatePage6SlotTypes(types) {
    applySlotTypes(types, 6)
}

def updatePage7SlotTypes(types) {
    applySlotTypes(types, 7)
}

def updatePage8SlotTypes(types) {
    applySlotTypes(types, 8)
}

def updatePage9SlotTypes(types) {
    applySlotTypes(types, 9)
}

def updatePage10SlotTypes(types) {
    applySlotTypes(types, 10)
}

def updatePage11SlotTypes(types) {
    applySlotTypes(types, 11)
}

def updatePage12SlotTypes(types) {
    applySlotTypes(types, 12)
}

private void applyLabels(labels, int page) {
    if (!(labels instanceof Map)) {
        try { labels = new groovy.json.JsonSlurper().parseText(labels.toString()) }
        catch (Exception e) { infoLog "[Dashboard] WARN -- bad labels JSON: ${e.message}"; return }
    }

    // Store labels in state only -- pushPageLayout publishes them at the right time,
    // after the layout objects exist on the display. Publishing here would race ahead
    // of clearpage/layout JSONL and hit "Unknown object" errors.
    labels.each { k, v ->
        int    idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        state[labelKey(page, idx)] = v?.toString() ?: ""
    }
    infoLog "[Dashboard] Labels stored for page ${page}: ${labels.size()} entries"
}

private void applySlotTypes(slotTypes, int page) {
    if (!(slotTypes instanceof Map)) {
        try { slotTypes = new groovy.json.JsonSlurper().parseText(slotTypes.toString()) }
        catch (Exception e) { infoLog "[Dashboard] WARN -- bad slotTypes JSON: ${e.message}"; return }
    }
    Map typeCounts = [:]
    slotTypes.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String t = (v?.toString() ?: "none")
        state[typeKey(page, idx)] = t
        if (t != "none") typeCounts[t] = ((typeCounts[t] ?: 0) as int) + 1
    }
    if (typeCounts) {
        String dominant = typeCounts.max { it.value }.key
        state["pageType${page}"] = dominant
        infoLog "[Dashboard] Page ${page} type: ${dominant}"
    }
}

// ── Light tile helper ──────────────────────────────────────────────────────────

// Sends only the properties that change for tappable tiles (color, text, click).
// Updates tappable tile: color, click:true, label in text, icon in value_str (top-left).
private String buildLightTileJsonl(int page, int slot, String grid, String bgColor = "#000000", String label = "") {
    String sType       = state[typeKey(page, slot)] ?: "none"
    String activeState = state[stateKey(page, slot)] ?: "inactive"
    String icon        = (sType == "light")  ? (activeState == "active" ? ICON_LIGHTBULB_ON() : ICON_LIGHTBULB_OFF()) :
                         (sType == "lock")   ? (activeState == "active" ? ICON_LOCK_OPEN()    : ICON_LOCK())          :
                         (sType == "garage") ? (activeState == "active" ? ICON_GARAGE_OPEN()  : ICON_GARAGE())        : ""
    String escapedIcon  = iconToJsonEscape(icon)
    String escapedLabel = labelToJsonEscape(label)
    String contrast     = contrastColor(bgColor)
    int    iFont        = iconFontFor(grid)
    int[]  ofs          = iconOffsetsFor(grid)
    int    lFont        = labelFontFor(grid, label)
    int    padTop       = labelPadTopFor(grid)
    return """{"page":${page},"id":${bgId(slot)},"bg_color":"${bgColor}","text_color":"${contrast}","text":"${escapedLabel}","text_font":${lFont},"align":"center","pad_top":${padTop},"value_str":"${escapedIcon}","value_font":${iFont},"value_ofs_x":${ofs[0]},"value_ofs_y":${ofs[1]},"value_color":"${contrast}","click":true}"""
}

// ── Rotation ───────────────────────────────────────────────────────────────────

def rotatePage() {
    if (state.pushInProgress) return
    int total = (state.numberOfPages ?: 1) as int
    if (total < 2) return
    int cur  = (state.rotationPage ?: 1) as int
    int next = (cur % total) + 1
    state.rotationPage = next
    String node = settings.haspNode ?: "plate"
    safePub("hasp/${node}/command/page", "${next}")
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (rotInt > 0) runIn(rotInt, "rotatePage")
}

// ── Fade engine ────────────────────────────────────────────────────────────────

@Field static final int FADE_STEPS = 10

private int fadeInterval() {
    int dur = (settings.fadeDuration ?: 30) as int
    return Math.max(1, (int)(dur / FADE_STEPS))
}

private void scheduleFadeStep(int page, int idx) {
    int interval = fadeInterval()
    switch ("${page}_${idx}") {
        // Hubitat requires literal method names for runIn, so we encode page+slot in method name.
        // We support up to page 6, slot 49. Schedules fire fadeContinue which reads state.
        default:
            // Generic path: store page+idx for the single shared handler approach
            state["fadeTarget_${page}_${idx}"] = true
            runIn(interval, "fadeContinueAll")
            break
    }
}

def fadeContinueAll() {
    // Find all in-progress fades and advance them
    int numPg = (state.numberOfPages ?: 12) as int
    boolean anyRemaining = false
    (1..numPg).each { pg ->
        int ms = maxSensors(pg)
        if (ms < 1) return
        (1..ms).each { idx ->
            String fadeKey = "p${pg}fadeStep${idx}"
            def stepObj = state[fadeKey]
            if (stepObj == null) return
            int step = (stepObj as int) + 1
            if (step >= FADE_STEPS) {
                state.remove(fadeKey)
                state.remove("fadeTarget_${pg}_${idx}")
                String ic = inactiveColorFor(pg, idx)
                publishColor(pg, idx, ic)
            } else {
                state[fadeKey] = step
                anyRemaining = true
                // Interpolate from active red to inactive color
                String ic    = inactiveColorFor(pg, idx)
                String color = interpolateColor("#FF0000", ic, step, FADE_STEPS)
                publishColor(pg, idx, color)
            }
        }
    }
    if (anyRemaining) runIn(fadeInterval(), "fadeContinueAll")
}

private String interpolateColor(String from, String to, int step, int total) {
    try {
        int r1 = Integer.parseInt(from.substring(1,3), 16)
        int g1 = Integer.parseInt(from.substring(3,5), 16)
        int b1 = Integer.parseInt(from.substring(5,7), 16)
        int r2 = Integer.parseInt(to.substring(1,3), 16)
        int g2 = Integer.parseInt(to.substring(3,5), 16)
        int b2 = Integer.parseInt(to.substring(5,7), 16)
        float t  = (float)step / total
        int r    = (int)(r1 + (r2-r1)*t)
        int g    = (int)(g1 + (g2-g1)*t)
        int b    = (int)(b1 + (b2-b1)*t)
        return String.format("#%02X%02X%02X", r, g, b)
    } catch (Exception e) {
        return to
    }
}

// ── Publish helpers ────────────────────────────────────────────────────────────

private void publishColor(int page, int idx, String color) {
    String node     = settings.haspNode ?: "plate"
    String contrast = contrastColor(color)
    // Single JSONL updates bg_color and text_color atomically -- no render between them
    String jsonl = '{"page":' + page + ',"id":' + bgId(idx) + ',"bg_color":"' + color + '","text_color":"' + contrast + '","value_color":"' + contrast + '"}'
    safePub("hasp/" + node + "/command/jsonl", jsonl)
}

private String labelToJsonEscape(String s) {
    if (!s) return ""
    def hexDigits = ['0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F']
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < s.length(); i++) {
        int cp = (int) s.charAt(i)
        if (cp > 127) {
            sb.append('\\').append('u')
            sb.append(hexDigits[(cp >> 12) & 0xF])
            sb.append(hexDigits[(cp >>  8) & 0xF])
            sb.append(hexDigits[(cp >>  4) & 0xF])
            sb.append(hexDigits[ cp        & 0xF])
        } else if (cp == (int)'"') {
            sb.append('\\"')
        } else if (cp == (int)'\n') {
            sb.append('\\n')
        } else {
            sb.append((char) cp)
        }
    }
    return sb.toString()
}

// Send icon as value_str (top-left corner) and label as text (centered).
// value_str survives Hubitat MQTT intact since it goes via JSONL, not raw topic.
private void publishIconJsonl(String node, int page, int idx, String icon, String bgColor = null) {
    try {
        String escaped    = iconToJsonEscape(icon)
        String lbl        = state[labelKey(page, idx)] ?: ""
        String escapedLbl = labelToJsonEscape(lbl)
        String grid       = activeGrid(page)
        int    iFont      = iconFontFor(grid)
        int[]  ofs        = iconOffsetsFor(grid)
        int    lFont      = labelFontFor(grid, lbl)
        int    padTop     = labelPadTopFor(grid)
        String tColor     = bgColor ? contrastColor(bgColor) : "white"

        String jsonl = '{"page":' + page + ',"id":' + bgId(idx) +
            ',"text":"' + escapedLbl + '"' +
            ',"text_font":' + lFont +
            ',"align":"center"' +
            ',"pad_top":' + padTop +
            ',"value_str":"' + escaped + '"' +
            ',"value_font":' + iFont +
            ',"value_ofs_x":' + ofs[0] +
            ',"value_ofs_y":' + ofs[1] +
            ',"value_color":"' + tColor + '"'
        if (bgColor) jsonl += ',"bg_color":"' + bgColor + '","text_color":"' + tColor + '"'
        jsonl += '}'
        debugLog "[Dashboard] Tile JSONL p${page}s${idx}: ${jsonl}"
        safePub("hasp/" + node + "/command/jsonl", jsonl)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- publishIconJsonl p${page}s${idx}: ${e.message}"
    }
}

// Convert an icon glyph string to JSON \\uXXXX escapes.
// Handles any Unicode character and multi-char strings (e.g. double water drop).
private String iconToJsonEscape(String s) {
    if (!s) return ""
    def hexDigits = ['0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F']
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < s.length(); i++) {
        int cp = (int) s.charAt(i)
        if (cp > 127) {
            sb.append('\\').append('u')
            sb.append(hexDigits[(cp >> 12) & 0xF])
            sb.append(hexDigits[(cp >>  8) & 0xF])
            sb.append(hexDigits[(cp >>  4) & 0xF])
            sb.append(hexDigits[ cp        & 0xF])
        } else {
            sb.append((char) cp)
        }
    }
    return sb.toString()
}

// Returns black or white depending on which contrasts better with the given hex color.
private String contrastColor(String hex) {
    try {
        String h = hex.startsWith("#") ? hex.substring(1) : hex
        int r = Integer.parseInt(h.substring(0,2), 16)
        int g = Integer.parseInt(h.substring(2,4), 16)
        int b = Integer.parseInt(h.substring(4,6), 16)
        // Perceived luminance (WCAG formula)
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return (lum > 0.55) ? "#000000" : "#FFFFFF"
    } catch (Exception e) {
        return "#FFFFFF"
    }
}

// ── Icon constants ─────────────────────────────────────────────────────────────

// Icon glyphs -- codes from official openHASP 0.7 font table:
// https://www.openhasp.com/0.7.0/design/fonts/
private String ICON_MOTION_ACTIVE()   { return "" }
private String ICON_MOTION_INACTIVE() { return "" }
private String ICON_SMOKE_ACTIVE()    { return "\uE026" }   // alert
private String ICON_SMOKE_INACTIVE()  { return "\uE238" }   // fire
private String ICON_WATER_WET()       { return "\uE58C\uE58C" }   // two water drops (wet)
private String ICON_WATER_DRY()       { return "\uE58C" }          // single water drop (dry)
private String ICON_DOOR_OPEN()       { return "\uF208" }   // door-open
private String ICON_DOOR_CLOSED()     { return "\uE81C" }   // door
private String ICON_WINDOW_OPEN()     { return "\uF6A3" }   // window-open
private String ICON_WINDOW_CLOSED()   { return "\uF1DB" }   // window
private String ICON_LIGHTBULB_ON()    { return "\uE6E8" }   // lightbulb-on
private String ICON_LIGHTBULB_OFF()   { return "\uE335" }   // lightbulb
private String ICON_LOCK_OPEN()       { return "\uEFC6" }   // lock-open-variant
private String ICON_LOCK()            { return "\uE33E" }   // lock
private String ICON_GARAGE_OPEN()     { return "\uF2D4" }   // garage-open-variant
private String ICON_GARAGE()          { return "\uF2D3" }   // garage-variant

// Weather condition icons -- codepoints from the same openHASP 0.7 font table.
private String ICON_WEATHER_SUNNY()              { return "\uE599" }
private String ICON_WEATHER_NIGHT()               { return "\uE594" }
private String ICON_WEATHER_PARTLY_CLOUDY()       { return "\uE595" }
private String ICON_WEATHER_NIGHT_PARTLY_CLOUDY() { return "\uEF31" }
private String ICON_WEATHER_CLOUDY()              { return "\uE590" }
private String ICON_WEATHER_POURING()             { return "\uE596" }
private String ICON_WEATHER_RAINY()               { return "\uE597" }
private String ICON_WEATHER_LIGHTNING()           { return "\uE593" }
private String ICON_WEATHER_LIGHTNING_RAINY()     { return "\uE67E" }
private String ICON_WEATHER_SNOWY()               { return "\uE598" }
private String ICON_WEATHER_SNOWY_RAINY()         { return "\uE67F" }
private String ICON_WEATHER_FOG()                 { return "\uE591" }
private String ICON_WEATHER_HAZY()                { return "\uEF30" }
private String ICON_WEATHER_WINDY()               { return "\uE59D" }
private String ICON_WEATHER_HAIL()                { return "\uE592" }

// Maps the app's normalized condition key (see weatherConditionKey() in the
// app) to a glyph. All icon codepoint knowledge stays here in the driver,
// matching the rest of this file's icon architecture.
private String weatherIconGlyph(String key) {
    switch (key) {
        case "sunny":              return ICON_WEATHER_SUNNY()
        case "night":              return ICON_WEATHER_NIGHT()
        case "partly-cloudy":      return ICON_WEATHER_PARTLY_CLOUDY()
        case "night-partly-cloudy":return ICON_WEATHER_NIGHT_PARTLY_CLOUDY()
        case "cloudy":             return ICON_WEATHER_CLOUDY()
        case "pouring":            return ICON_WEATHER_POURING()
        case "rainy":              return ICON_WEATHER_RAINY()
        case "lightning":          return ICON_WEATHER_LIGHTNING()
        case "lightning-rainy":    return ICON_WEATHER_LIGHTNING_RAINY()
        case "snowy":              return ICON_WEATHER_SNOWY()
        case "snowy-rainy":        return ICON_WEATHER_SNOWY_RAINY()
        case "fog":                return ICON_WEATHER_FOG()
        case "hazy":               return ICON_WEATHER_HAZY()
        case "windy":              return ICON_WEATHER_WINDY()
        case "hail":               return ICON_WEATHER_HAIL()
        default:                   return ICON_WEATHER_CLOUDY()
    }
}

private String activeIconFor(int page, int idx) {
    String t = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (t) {
        case "smoke":   return ICON_SMOKE_ACTIVE()
        case "water":   return ICON_WATER_WET()
        case "door":    return ICON_DOOR_OPEN()
        case "window":  return ICON_WINDOW_OPEN()
        case "contact": return ICON_DOOR_OPEN()
        case "lock":    return ICON_LOCK_OPEN()
        case "garage":  return ICON_GARAGE_OPEN()
        default:        return ICON_MOTION_ACTIVE()
    }
}

private String inactiveIconFor(int page, int idx) {
    String t = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (t) {
        case "smoke":   return ICON_SMOKE_INACTIVE()
        case "water":   return ICON_WATER_DRY()
        case "door":    return ICON_DOOR_CLOSED()
        case "window":  return ICON_WINDOW_CLOSED()
        case "contact": return ICON_DOOR_CLOSED()
        case "light":   return ICON_LIGHTBULB_OFF()
        case "lock":    return ICON_LOCK()
        case "garage":  return ICON_GARAGE()
        default:        return ICON_MOTION_INACTIVE()
    }
}

private String inactiveColorFor(int page, int idx) {
    String t = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (t) {
        case "door":    return (settings.colorDoorInactive    ?: "#00FFFF")
        case "window":  return (settings.colorWindowInactive  ?: "#00FFFF")
        case "contact": return (settings.colorContactInactive ?: "#00FFFF")
        case "water":   return (settings.colorWaterInactive   ?: "#0000FF")
        case "smoke":   return (settings.colorSmokeInactive   ?: "#808080")
        case "light":   return (settings.colorLightInactive   ?: "#808080")
        case "lock":    return (settings.colorLockInactive    ?: "#1B5E20")   // dark green = locked/safe
        case "garage":  return (settings.colorGarageInactive  ?: "#1B5E20")   // dark green = closed/safe
        default:        return (settings.colorInactive        ?: "#008000")
    }
}

// ── Logging ────────────────────────────────────────────────────────────────────

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
