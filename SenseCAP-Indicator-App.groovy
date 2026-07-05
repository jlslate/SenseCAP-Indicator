/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Indicator App v2.7.1
 *
 * Hubitat app companion to SenseCAP Indicator Driver.
 * Manages up to 12 display pages on the SenseCAP Indicator D1 via openHASP/MQTT.
 *
 * Key features:
 * - Up to 12 pages, each with independent sensor type and device selection
 * - Page type can be "mixed" -- each slot picks its own sensor type + device;
 *   slots are added one at a time via "Add another device" (grid auto-sizes),
 *   and each slot has a "Remove slot" button (shifts later slots down)
 * - Slot labels can be overridden per-slot (single-type and mixed pages)
 * - Supported types: smoke / motion / water / contact / light (switch) /
 *   temperature / humidity / illumination / weather / clock (mixed-only)
 * - Temperature/humidity/illumination/weather: low/high threshold per page
 *   (per-slot on mixed pages) drives value-tile coloring on the display
 * - Clock (mixed-slot only, no device): time and/or date, 12/24-hour, no seconds
 * - Grid auto-sized 2x1 to 5x5 based on device count (2x1 for exactly 2)
 * - Page display order adjustable via Move Up / Move Down buttons
 * - Remove button on each page (except first) to delete pages
 * - Add Page toggle appears at bottom of last page section
 * - Devices sorted alphabetically (emoji-stripped) within each page
 * - Light pages: tap-to-toggle via MQTT button events; periodic re-sync (configurable)
 * - Tap-to-navigate: any type not already tappable (light/lock/garage toggle;
 *   thermostat has its own controls) can be set to jump to another page on
 *   tap, then auto-revert after an adjustable delay (renderTapNavInputs /
 *   pushPageTapConfig)
 * - Sensor events gated during layout push via appPushInProgress state flag
 * - syncAllSensors fires after returnToPage1 so all states correct after render
 * - On save/reboot: rebootDisplay sent if MQTT connected, LWT triggers re-push
 * - getPageOrder() for UI (all pages); activePageOrder() for driver operations
 *
 * Changelog:
 * v2.7.1 -- Converted all dynamic-dispatch driver calls
 *           (indicatorDevice."methodName${page}Suffix"(...)) to direct calls
 *           against the driver's new consolidated commands, with page passed
 *           as an explicit argument, matching driver v2.7.1's 113->9 command
 *           consolidation. Added a per-page "Don't switch to this page
 *           automatically" checkbox (pageXBlockAutoSwitch), pushed via
 *           setPageBlockAutoSwitch immediately in initialize() on every save
 *           -- deliberately NOT gated behind layoutFingerprint/the
 *           reboot+pushSlotTypesAndLayouts cycle, since it's just a driver
 *           state flag and doesn't need a display rebuild to take effect.
 *           Fixed layoutFingerprint() to include per-slot custom label text
 *           (pageXSlotLabelN / MixedSlotNLabel) -- previously a label-only
 *           edit never changed the fingerprint, so pushPageLabels never
 *           re-ran and the new label silently never reached the driver.
 *           Fixed the same "0 is falsy" Elvis-operator bug as the driver:
 *           tap-nav's Revert-after-seconds field ignored an explicit 0
 *           (meant to disable auto-revert) and fell back to 10s in both
 *           pushPageTapConfig branches (dedicated-page and mixed-slot).
 * v2.1.0 -- Mixed-page slots can now be reordered: Move Up/Down buttons next
 *           to each slot's Remove button call swapMixedSlotSettings, which
 *           does a true swap (via a scratch slot past maxMixedSlots()) of
 *           the full settings bundle -- type, device, label, thresholds,
 *           clock options, and tap-nav. Also fixed copyMixedSlotSettings/
 *           clearMixedSlotSettings to include tap-nav (TapEnabled/TapTarget/
 *           TapRevertSeconds) -- previously "Remove slot" silently left a
 *           deleted slot's tap-nav config behind on whatever slot shifted
 *           into its place.
 * v2.0.0 -- Renamed throughout from "SenseCAP Dashboard and Thermostat" to
 *           "SenseCAP Indicator" (definition's name field, the mainPage
 *           title, the "Select your ... device" input label, and this file's
 *           own header/companion-driver reference), matching the hardware's
 *           own name. Version reset to 2.0.0 to mark this rename as a fresh
 *           baseline -- see the entries below for everything that shipped
 *           under the old name and version numbers.
 * v2.6.0 -- Lock/garage are no longer "already tappable" (isTappableAlready):
 *           their short tap is now eligible for tap-to-navigate just like
 *           any other type, while a long-press still brings up the
 *           lock/unlock or open/close confirmation (driver's
 *           handleButtonTap). renderTapNavInputs takes an optional sType so
 *           its settings note can clarify this split for lock/garage.
 * v2.5.0 -- Added a horizontal rule before each Page section in the settings
 *           UI, so consecutive pages are easier to tell apart while
 *           scrolling (same motivation as v2.3.0's Slot dividers). The rule
 *           is folded into the section's own title string rather than a
 *           standalone paragraph{} -- Hubitat throws a NullPointerException
 *           ("Cannot get property 'body' on null object") on any element
 *           placed outside a section.
 * v2.4.0 -- Weather's summary text now depends on the page's tile count
 *           (pageDeviceCount): 4 or fewer tiles gets the full temp/humidity/
 *           pressure/wind block, more than 4 collapses to just the
 *           temperature (formatWeatherSummary's new compact param) since
 *           there's no room for 5 lines on denser grids. pushNumericSlotValue
 *           now takes srcPage to look this up.
 * v2.3.0 -- Mixed-page slots now get a divider ("Slot N" + rule) between them
 *           in the settings UI, and the "Tap to show another page" toggle
 *           moved up to right after the type/device picker (was after label
 *           + thresholds) -- both make it harder to enable tap-nav on the
 *           wrong slot while scrolling a page with several similar slots.
 * v2.2.0 -- Tap-to-navigate for every type not already tappable (light/lock/
 *           garage already toggle; thermostat already has tap controls):
 *           "Tap to show another page" + target page + revert-after-seconds,
 *           on both dedicated pages and Mixed slots (renderTapNavInputs).
 *           Target is stored as a stable source-page number and resolved to
 *           the current display position at push time (pushPageTapConfig),
 *           so it keeps pointing at the same page even after reordering.
 * v2.1.0 -- Added a "2x1" grid tier (nxnString/nxnSlots) for pages with
 *           exactly 2 devices, so they get 2 tall side-by-side tiles
 *           instead of a 2x2 grid with 2 empty cells.
 *           Expanded max pages from 6 to 12: numberOfPages()/maxPages()
 *           rewritten as a cascading loop (matches mixedSlotCount()'s
 *           style); the page-rendering condition, add-page toggle, and
 *           delete-page reset all simplified from hardcoded 6-case
 *           chains/switches to loop- or arithmetic-based checks (e.g. the
 *           old `srcPage==N && addPage2 && ... && addPageN` chain is just
 *           `srcPage <= numberOfPages()`); layoutFingerprint()'s explicit
 *           addPage2..6 checks and (1..6) loop now scale via maxPages().
 *           Also converted pushPageSlotTypes/pushPageLabels/
 *           pushPageClockConfig/pushNumericSlotValue from switch/case
 *           dispatch to dynamic method-name calls (matches the style
 *           already used in handleEvent/syncAllSensors), so none of these
 *           needed new cases added for pages 7-12 at all.
 * v2.0.0 -- Added a "Remove slot" button on each Mixed-page slot (shifts
 *           later slots' full settings -- type, device, label, thresholds,
 *           clock options -- down by one via copyMixedSlotSettings/
 *           clearMixedSlotSettings, then drops the top MixedAddSlot flag).
 *           Fix: windDirLabel() called Math.round(BigDecimal) with no
 *           matching overload, silently falling to the catch block and
 *           always returning the raw degree number -- now converts to
 *           double explicitly first.
 * v1.9.0 -- Wind is now two lines ("Wind Speed" / "Wind Direction") instead
 *           of one combined line. windDirLabel() switched from a 16-point to
 *           an 8-point compass (360/8 = 45° per sector, N centered on 0°).
 * v1.8.2 -- Fix: formatWeatherSummary() never included temperature (only
 *           humidity/pressure/wind) even though Weather's threshold check
 *           already reads it -- now passes the already-fetched value through
 *           and adds a "Temp: X°" line.
 * v1.8.1 -- Fix: icon attribute candidate list only tried "weatherIcon"
 *           (singular); this device's driver reports "weatherIcons" (plural)
 *           -- added it as the first candidate.
 * v1.8.0 -- Weather now shows a curated summary (humidity/pressure/wind
 *           speed+direction, trying a few common attribute-name variants
 *           per metric) instead of a dump of every attribute, plus a
 *           condition icon. weatherConditionKey() maps the device's
 *           OpenWeatherMap-style icon code (or failing that, its text
 *           condition) to a normalized key; the driver owns the actual glyph.
 * v1.7.1 -- Fix: Weather's attribute dump used dev.supportedAttributes (the
 *           declared capability schema), which misses attributes that a
 *           driver reports via sendEvent() without formally declaring them --
 *           common with community weather integrations (e.g. OpenWeatherMap).
 *           Switched to dev.currentStates, which reflects what's actually
 *           been reported, so humidity/wind/forecast/etc. now show up too.
 * v1.7.0 -- Numeric types now report a three-state "below"/"within"/"above"
 *           threshold result (not just in/out of range) so the driver can use
 *           different below/above colors per type. Weather no longer sends a
 *           device-name label -- it dumps every current attribute on the
 *           device instead of a single value.
 * v1.6.1 -- Fix: layoutFingerprint() didn't include clock display options or
 *           numeric low/high thresholds, so tweaking them after the slot type
 *           was already set wouldn't push to the driver. Also matches the
 *           driver's v1.6.1 fix for numeric/clock tiles showing the wrong
 *           default icon on first render.
 * v1.6.0 -- Added temperature/humidity/illumination/weather (numeric value,
 *           low/high threshold coloring) and Clock (mixed-slot only, no
 *           device) sensor types, available as dedicated pages (except
 *           Clock) and as Mixed-page slot types.
 * v1.5.0 -- Mixed pages no longer ask for a grid size up front. Instead, an
 *           "Add another device" toggle appears after each slot (same pattern
 *           as adding pages), and the grid auto-sizes from the slot count.
 * v1.3.1 -- Fix: Home auto-control now reads current temp and sets thermostat
 *           mode (heat/cool/off) to keep temp within home setpoints, matching
 *           the Away path behaviour. Also calls runAutoControlAllPages from
 *           thermostatPeriodicSync so drift is caught between mode changes.
 *
 * Author: jlslate (slate)
 * Version: 2.7.1
 */

definition(
    name: "SenseCAP Indicator",
    namespace: "jlslate",
    author: "jlslate (slate)",
    description: "Auto-assigns sensors to pages by type on a SenseCAP Indicator display via MQTT",
    category: "Integration",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Indicator", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name: "indicatorDevice",
                  type: "capability.actuator",
                  title: "Select your SenseCAP Indicator device",
                  required: true,
                  multiple: false
        }

        List<Integer> ord = getPageOrder()
        int totalPages = numberOfPages()

        // Pages -- shown in current display order with inline move buttons
        ord.eachWithIndex { srcPage, dispIdx ->
            int dispPos = dispIdx + 1
            boolean canUp = (dispPos > 1)
            boolean canDn = (dispPos < ord.size())

            if (srcPage <= totalPages) {

                section("<hr style='border:none;border-top:6px solid #1976D2;margin:0 0 8px 0;'><b>Page ${dispPos}</b>") {
                    input name: "page${srcPage}Type",
                          type: "enum",
                          title: "Page type",
                          options: pageTypeOptions(),
                          required: false,
                          defaultValue: null,
                          submitOnChange: true,
                          width: 6

                    if (canUp) {
                        input name: "moveUp_${dispPos}",
                              type: "button",
                              title: "&#9650; Move Up",
                              width: canDn ? 2 : 3
                    }
                    if (canDn) {
                        input name: "moveDn_${dispPos}",
                              type: "button",
                              title: "&#9660; Move Down",
                              width: canUp ? 2 : 3
                    }
                    if (dispPos > 1) {
                        input name: "deletePage_${srcPage}",
                              type: "button",
                              title: "&#10006; Remove",
                              width: 2
                    }

                    String pType = settings["page${srcPage}Type"] ?: ""

                    if (pType) {
                        input name: "page${srcPage}BlockAutoSwitch",
                              type: "bool",
                              title: "Don't switch to this page automatically when its sensor is active",
                              defaultValue: false,
                              submitOnChange: true,
                              width: 12
                    }

                    if (pType == "mixed") {
                        renderMixedPageInputs(srcPage)
                    } else if (pType == "thermostat") {
                        input name: "page${srcPage}ThermostatDevice",
                              type: "capability.thermostat",
                              title: "Select thermostat",
                              required: false,
                              multiple: false,
                              submitOnChange: true
                        input name: "page${srcPage}ThermostatTempSensor",
                              type: "capability.temperatureMeasurement",
                              title: "Separate temperature sensor (optional)",
                              required: false,
                              multiple: false
                        paragraph "<b>Away/Home limits</b>"
                        input name: "page${srcPage}VarAwayHigh",  type: "number", title: "Away high",  required: false, width: 6
                        input name: "page${srcPage}VarAwayLow",   type: "number", title: "Away low",   required: false, width: 6
                        input name: "page${srcPage}VarHereHigh",  type: "number", title: "Home high",  required: false, width: 6
                        input name: "page${srcPage}VarHereLow",   type: "number", title: "Home low",   required: false, width: 6
                        paragraph "Fixed 2x2 layout: temp/status | + | mode | −"
                    } else if (pType) {
                        String devInputName = "page${srcPage}Devices_${pType}"
                        input name: devInputName,
                              type: capabilityFor(pType),
                              title: "Select devices",
                              required: false,
                              multiple: true,
                              submitOnChange: true
                        List selDevs = settings[devInputName] ?: []
                        int cnt = selDevs.size()
                        paragraph "Devices: <b>${cnt}</b> &rarr; Grid: <b>${nxnString(cnt)}</b>"
                        if (!isTappableAlready(pType)) {
                            renderTapNavInputs("page${srcPage}", srcPage, pType)
                        }
                        if (isNumericType(pType)) {
                            paragraph "<b>Threshold</b> (color changes when value is outside this range)"
                            input name: "page${srcPage}NumLow",  type: "decimal", title: "Low",  required: false, width: 6
                            input name: "page${srcPage}NumHigh", type: "decimal", title: "High", required: false, width: 6
                        }
                        if (cnt > 0) {
                            input name: "page${srcPage}RenameSlots",
                                  type: "bool",
                                  title: "Rename or rearrange slots",
                                  defaultValue: false,
                                  submitOnChange: true
                            if (settings["page${srcPage}RenameSlots"]) {
                                List sortedDevs = sortDevicesForPage(srcPage, selDevs)
                                int totalDevs = sortedDevs.size()
                                sortedDevs.eachWithIndex { dev, idx ->
                                    if (!dev) return
                                    int slot = idx + 1
                                    String hint = stripEmoji(dev.displayName ?: "")
                                    input name: "page${srcPage}SlotPos${dev.id}",
                                          type: "number",
                                          title: "Position",
                                          required: false,
                                          defaultValue: slot,
                                          range: "1..${totalDevs}",
                                          submitOnChange: true,
                                          width: 2
                                    input name: "page${srcPage}SlotLabel${slot}",
                                          type: "text",
                                          title: "Label (default: ${hint})",
                                          required: false,
                                          defaultValue: "",
                                          width: 10
                                }
                            }
                        }
                    }

                    // Add-page toggle at the last displayed page
                    int activePages = ord.size()
                    boolean isLastDisplayed = (dispPos == activePages)
                    if (isLastDisplayed && activePages < maxPages()) {
                        paragraph "<hr style='border:none;border-top:6px solid #1976D2;margin:8px 0;'>"
                        input name: "addPage${activePages + 1}",
                              type: "bool",
                              title: "Add page ${activePages + 1}",
                              defaultValue: false,
                              submitOnChange: true
                    }
                }
            }
        }

        section("<b>Options</b>") {
            input name: "syncOnStartup",
                  type: "bool",
                  title: "Sync all sensor states on startup/save",
                  defaultValue: true
            input name: "lightSyncInterval",
                  type: "enum",
                  title: "Re-sync light states every",
                  options: ["0":"Never","5":"5 minutes","10":"10 minutes","30":"30 minutes"],
                  defaultValue: "10"
            input name: "logLevel",
                  type: "enum",
                  title: "Logging Level",
                  options: ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1",
                  required: true
        }

        section("<b>Status</b>") {
            int total = activePageOrder().size()
            int devices = activePageOrder().sum { pg -> pageDeviceCount(pg) } as int
            paragraph "Pages: <b>${total}</b> -- Total devices: <b>${devices}</b>"
            if (settings.indicatorDevice) {
                paragraph "MQTT status: <b>${settings.indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

// Render per-slot inputs for a mixed page.
// Slot 1 is always shown; an "Add another device" toggle after the last
// active slot reveals the next one -- same pattern as the Add Page toggles.
// The grid auto-sizes from the number of active slots (see nxnString()).
private void renderMixedPageInputs(int srcPage) {
    int totalSlots = mixedSlotCount(srcPage)

    paragraph "Devices: <b>${totalSlots}</b> &rarr; Grid: <b>${nxnString(totalSlots)}</b>"

    (1..totalSlots).each { slot ->
        String typeKey = "page${srcPage}MixedSlot${slot}Type"
        String slotType = settings[typeKey] ?: ""

        paragraph "<hr style='border:none;border-top:3px solid #888;margin:16px 0 8px 0;'><b>Slot ${slot}</b>"

        input name: typeKey,
              type: "enum",
              title: "Slot ${slot} type",
              options: sensorTypeOptions(),
              required: false,
              defaultValue: null,
              submitOnChange: true,
              width: 4

        if (totalSlots > 1) {
            if (slot > 1) {
                input name: "page${srcPage}MixedSlotUp${slot}",
                      type: "button",
                      title: "&#9650; Move Up",
                      width: 2
            }
            if (slot < totalSlots) {
                input name: "page${srcPage}MixedSlotDn${slot}",
                      type: "button",
                      title: "&#9660; Move Down",
                      width: 2
            }
            input name: "page${srcPage}MixedDeleteSlot${slot}",
                  type: "button",
                  title: "&#10006; Remove slot ${slot}",
                  width: 2
        }

        if (slotType == "clock") {
            renderTapNavInputs("page${srcPage}MixedSlot${slot}", srcPage)
            input name: "page${srcPage}MixedSlot${slot}ClockShowTime",
                  type: "bool", title: "Slot ${slot}: show time", defaultValue: true, submitOnChange: true, width: 4
            input name: "page${srcPage}MixedSlot${slot}ClockShowDate",
                  type: "bool", title: "Slot ${slot}: show date", defaultValue: true, submitOnChange: true, width: 4
            input name: "page${srcPage}MixedSlot${slot}ClockFormat",
                  type: "enum", title: "Slot ${slot}: format", options: ["12":"12-hour","24":"24-hour"],
                  defaultValue: "12", submitOnChange: true, width: 4
        } else if (slotType) {
            String devKey = "page${srcPage}MixedSlot${slot}Device_${slotType}"
            def slotDev = settings[devKey]
            input name: devKey,
                  type: capabilityFor(slotType),
                  title: "Slot ${slot} device",
                  required: false,
                  multiple: false,
                  submitOnChange: true,
                  width: slotDev ? 5 : 8
            if (slotDev) {
                if (!isTappableAlready(slotType)) {
                    renderTapNavInputs("page${srcPage}MixedSlot${slot}", srcPage, slotType)
                }
                String hint = stripEmoji(slotDev.displayName ?: "")
                input name: "page${srcPage}MixedSlot${slot}Label",
                      type: "text",
                      title: "Slot ${slot} label (default: ${hint})",
                      required: false,
                      defaultValue: "",
                      width: 3
                if (isNumericType(slotType)) {
                    input name: "page${srcPage}MixedSlot${slot}Low",  type: "decimal", title: "Slot ${slot} low threshold",  required: false, width: 6
                    input name: "page${srcPage}MixedSlot${slot}High", type: "decimal", title: "Slot ${slot} high threshold", required: false, width: 6
                }
            }
        }

        if (slot == totalSlots && totalSlots < maxMixedSlots()) {
            input name: "page${srcPage}MixedAddSlot${slot + 1}",
                  type: "bool",
                  title: "Add another device",
                  defaultValue: false,
                  submitOnChange: true
        }
    }
}

// ── Option maps ───────────────────────────────────────────────────────────────

private Map pageTypeOptions() {
    ["smoke":"Smoke detectors",
     "motion":"Motion sensors",
     "water":"Water sensors",
     "door":"Door sensors",
     "window":"Window sensors",
     "contact":"Contact sensors",
     "lock":"Locks",
     "garage":"Garage doors",
     "light":"Lights (switch)",
     "thermostat":"Thermostat",
     "temperature":"Temperature sensors",
     "humidity":"Humidity sensors",
     "illumination":"Illumination sensors",
     "weather":"Weather devices",
     "mixed":"Mixed (any sensor per slot)"]
}

private Map sensorTypeOptions() {
    ["smoke":"Smoke",
     "motion":"Motion",
     "water":"Water",
     "door":"Door",
     "window":"Window",
     "contact":"Contact",
     "lock":"Lock",
     "garage":"Garage door",
     "light":"Light (switch)",
     "temperature":"Temperature",
     "humidity":"Humidity",
     "illumination":"Illumination",
     "weather":"Weather",
     "clock":"Clock"]
}

// Highest number of slots a mixed page can grow to (matches the 6x5 max grid).
private int maxMixedSlots() { return 30 }

// Number of active slots for a mixed page, based on cascading
// "Add another device" toggles (page{N}MixedAddSlot{2..max}).
private int mixedSlotCount(int page) {
    int max = maxMixedSlots()
    for (int n = 2; n <= max; n++) {
        if (!settings["page${page}MixedAddSlot${n}"]) return n - 1
    }
    return max
}

// Moves one mixed slot's full settings (type, device, label, thresholds,
// clock options) from srcSlot to dstSlot -- used to shift slots down after
// a delete so the remaining slots stay contiguous.
private void copyMixedSlotSettings(int page, int srcSlot, int dstSlot) {
    String type = settings["page${page}MixedSlot${srcSlot}Type"] ?: ""
    if (type) {
        app.updateSetting("page${page}MixedSlot${dstSlot}Type", [value: type, type: "enum"])
    } else {
        app.clearSetting("page${page}MixedSlot${dstSlot}Type")
    }

    if (type == "clock") {
        ["ClockShowTime", "ClockShowDate"].each { suffix ->
            def v = settings["page${page}MixedSlot${srcSlot}${suffix}"]
            if (v != null) app.updateSetting("page${page}MixedSlot${dstSlot}${suffix}", [value: v, type: "bool"])
            else app.clearSetting("page${page}MixedSlot${dstSlot}${suffix}")
        }
        String fmt = settings["page${page}MixedSlot${srcSlot}ClockFormat"]
        if (fmt) app.updateSetting("page${page}MixedSlot${dstSlot}ClockFormat", [value: fmt, type: "enum"])
        else app.clearSetting("page${page}MixedSlot${dstSlot}ClockFormat")
    } else if (type) {
        def dev = settings["page${page}MixedSlot${srcSlot}Device_${type}"]
        if (dev) app.updateSetting("page${page}MixedSlot${dstSlot}Device_${type}", [value: dev, type: capabilityFor(type)])
        else app.clearSetting("page${page}MixedSlot${dstSlot}Device_${type}")

        String lbl = settings["page${page}MixedSlot${srcSlot}Label"]
        if (lbl) app.updateSetting("page${page}MixedSlot${dstSlot}Label", [value: lbl, type: "text"])
        else app.clearSetting("page${page}MixedSlot${dstSlot}Label")

        if (isNumericType(type)) {
            ["Low", "High"].each { suffix ->
                def v = settings["page${page}MixedSlot${srcSlot}${suffix}"]
                if (v != null) app.updateSetting("page${page}MixedSlot${dstSlot}${suffix}", [value: v, type: "decimal"])
                else app.clearSetting("page${page}MixedSlot${dstSlot}${suffix}")
            }
        }
    }

    // Tap-nav (see renderTapNavInputs) is independent of the type-specific
    // branches above -- clock and device slots can both have it.
    def tapEnabled = settings["page${page}MixedSlot${srcSlot}TapEnabled"]
    if (tapEnabled != null) app.updateSetting("page${page}MixedSlot${dstSlot}TapEnabled", [value: tapEnabled, type: "bool"])
    else app.clearSetting("page${page}MixedSlot${dstSlot}TapEnabled")

    def tapTarget = settings["page${page}MixedSlot${srcSlot}TapTarget"]
    if (tapTarget != null) app.updateSetting("page${page}MixedSlot${dstSlot}TapTarget", [value: tapTarget, type: "enum"])
    else app.clearSetting("page${page}MixedSlot${dstSlot}TapTarget")

    def tapRevert = settings["page${page}MixedSlot${srcSlot}TapRevertSeconds"]
    if (tapRevert != null) app.updateSetting("page${page}MixedSlot${dstSlot}TapRevertSeconds", [value: tapRevert, type: "number"])
    else app.clearSetting("page${page}MixedSlot${dstSlot}TapRevertSeconds")
}

// Clears all possible settings for one mixed slot (used on the last slot
// after shifting everything else down by one).
private void clearMixedSlotSettings(int page, int slot) {
    app.clearSetting("page${page}MixedSlot${slot}Type")
    sensorTypeOptions().keySet().each { t ->
        app.clearSetting("page${page}MixedSlot${slot}Device_${t}")
    }
    app.clearSetting("page${page}MixedSlot${slot}Label")
    app.clearSetting("page${page}MixedSlot${slot}Low")
    app.clearSetting("page${page}MixedSlot${slot}High")
    app.clearSetting("page${page}MixedSlot${slot}ClockShowTime")
    app.clearSetting("page${page}MixedSlot${slot}ClockShowDate")
    app.clearSetting("page${page}MixedSlot${slot}ClockFormat")
    app.clearSetting("page${page}MixedSlot${slot}TapEnabled")
    app.clearSetting("page${page}MixedSlot${slot}TapTarget")
    app.clearSetting("page${page}MixedSlot${slot}TapRevertSeconds")
}

// True swap of two mixed slots' full settings bundles (type, device, label,
// thresholds, clock/tap-nav options) -- via a scratch slot number just past
// maxMixedSlots(), which the UI never creates a real slot at, so neither
// side is clobbered mid-swap. Used by the per-slot Move Up/Down buttons.
private void swapMixedSlotSettings(int page, int slotA, int slotB) {
    int scratch = maxMixedSlots() + 1
    copyMixedSlotSettings(page, slotA, scratch)
    copyMixedSlotSettings(page, slotB, slotA)
    copyMixedSlotSettings(page, scratch, slotB)
    clearMixedSlotSettings(page, scratch)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private String capabilityFor(String sType) {
    switch (sType) {
        case "smoke":   return "capability.smokeDetector"
        case "motion":  return "capability.motionSensor"
        case "water":   return "capability.waterSensor"
        case "door":    return "capability.contactSensor"
        case "window":  return "capability.contactSensor"
        case "contact": return "capability.contactSensor"
        case "lock":    return "capability.lock"
        case "garage":  return "capability.garageDoorControl"
        case "light":       return "capability.switch"
        case "thermostat":  return "capability.thermostat"
        case "temperature": return "capability.temperatureMeasurement"
        case "humidity":    return "capability.relativeHumidityMeasurement"
        case "illumination":return "capability.illuminanceMeasurement"
        case "weather":     return "capability.sensor"
        default:            return "capability.sensor"
    }
}

private String attributeFor(String sType) {
    switch (sType) {
        case "smoke":   return "smoke"
        case "motion":  return "motion"
        case "water":   return "water"
        case "door":    return "contact"
        case "window":  return "contact"
        case "contact": return "contact"
        case "lock":    return "lock"
        case "garage":  return "door"
        case "light":   return "switch"
        case "temperature": return "temperature"
        case "humidity":    return "humidity"
        case "illumination":return "illuminance"
        case "weather":     return "temperature"
        default:        return "motion"
    }
}

// Numeric types render a value tile (with low/high threshold coloring)
// instead of the binary active/inactive icon tile.
private boolean isNumericType(String sType) {
    return (sType == "temperature" || sType == "humidity" || sType == "illumination" || sType == "weather")
}

// Light already tap-to-toggles on a short tap; thermostat already has its
// own tap controls. Everything else -- including lock/garage, whose short
// tap is now tap-to-navigate and whose long-press brings up the lock/unlock
// or open/close confirmation (see the driver's handleButtonTap) -- is
// eligible for the tap-to-navigate feature below.
private boolean isTappableAlready(String sType) {
    return (sType == "light" || sType == "thermostat")
}

// Target-page picker for tap-to-navigate: keyed by SOURCE page number (stable
// even if display order changes later), labeled with the page's CURRENT
// display position for clarity. Excludes the page being configured.
private Map otherPageOptions(int excludeSrcPage) {
    List<Integer> ord = getPageOrder()
    Map opts = [:]
    ord.eachWithIndex { pg, idx ->
        if (pg == excludeSrcPage) return
        int dispPos = idx + 1
        String label = (settings["page${pg}Type"] ?: "?") as String
        opts[pg.toString()] = "Page ${dispPos}: ${label.capitalize()}"
    }
    return opts
}

// Renders the "Tap to show page" toggle + target/revert inputs for one slot
// (dedicated page or mixed slot) -- shared so both call sites stay in sync.
// sType is only used to show a clarifying note for lock/garage, whose long
// tap still brings up the lock/unlock or open/close confirmation regardless
// of this setting -- only the short tap is being repurposed to navigate.
private void renderTapNavInputs(String prefix, int srcPage, String sType = "") {
    if (sType == "lock" || sType == "garage") {
        paragraph "Short tap navigates as configured below; long-press still asks to ${sType == 'lock' ? 'lock/unlock' : 'open/close'}."
    }
    input name: "${prefix}TapEnabled",
          type: "bool",
          title: "Tap to show another page",
          defaultValue: false,
          submitOnChange: true,
          width: 4
    if (settings["${prefix}TapEnabled"]) {
        input name: "${prefix}TapTarget",
              type: "enum",
              title: "Show page",
              options: otherPageOptions(srcPage),
              required: false,
              width: 4
        input name: "${prefix}TapRevertSeconds",
              type: "number",
              title: "Revert after (seconds)",
              required: false,
              defaultValue: 10,
              width: 4
    }
}

private String unitFor(String sType) {
    switch (sType) {
        case "temperature": return "°"
        case "weather":     return "°"
        case "humidity":    return "%"
        case "illumination":return " lx"
        default:            return ""
    }
}

private String activeValueFor(String sType) {
    switch (sType) {
        case "smoke":   return "detected"
        case "motion":  return "active"
        case "water":   return "wet"
        case "door":    return "open"
        case "window":  return "open"
        case "contact": return "open"
        case "lock":    return "unlocked"
        case "garage":  return "open"
        case "light":   return "on"
        default:        return "active"
    }
}

private int maxPages() { return 12 }

// Number of active pages, based on cascading "Add page" toggles
// (addPage2..addPageMaxPages) -- same pattern as mixedSlotCount().
private int numberOfPages() {
    int max = maxPages()
    for (int n = 2; n <= max; n++) {
        if (!settings["addPage${n}"]) return n - 1
    }
    return max
}

// Returns the flat ordered device list for a page (for non-mixed pages).
private List pageDevices(int page) {
    String sType = settings["page${page}Type"] ?: ""
    if (!sType || sType == "mixed") return []
    return (settings["page${page}Devices_${sType}"] ?: []) as List
}

// Returns total device count for a page (works for both mixed and single-type).
private int pageDeviceCount(int page) {
    String pType = settings["page${page}Type"] ?: ""
    if (pType == "mixed") {
        int count = 0
        (1..mixedSlotCount(page)).each { slot ->
            String slotType = settings["page${page}MixedSlot${slot}Type"] ?: ""
            if (slotType) {
                def dev = settings["page${page}MixedSlot${slot}Device_${slotType}"]
                if (dev) count++
            }
        }
        return count
    }
    if (pType == "thermostat") return settings["page${page}ThermostatDevice"] ? 1 : 0
    return pageDevices(page)?.size() ?: 0
}

private String pageType(int page) {
    return (settings["page${page}Type"] ?: "motion") as String
}

// Build a slot map: deviceId -> slotNumber (1-based).
// For mixed pages, slot numbers match the grid slot indices directly.
private Map buildSlotMap(int page, List devices, String sType) {
    Map m = [:]
    if (sType == "mixed") {
        (1..mixedSlotCount(page)).each { slot ->
            String slotType = settings["page${page}MixedSlot${slot}Type"] ?: ""
            if (slotType) {
                def dev = settings["page${page}MixedSlot${slot}Device_${slotType}"]
                if (dev) m[dev.id.toString()] = slot
            }
        }
    } else {
        devices.eachWithIndex { dev, idx ->
            if (dev) m[dev.id.toString()] = idx + 1
        }
    }
    return m
}

// Returns a List of [slot, sType, dev] triples for a page (all types combined).
// Used for sync and subscription -- works for both mixed and single-type pages.
private List<Map> pageSlotEntries(int page) {
    String pType = settings["page${page}Type"] ?: ""
    List<Map> entries = []
    if (pType == "mixed") {
        (1..mixedSlotCount(page)).each { slot ->
            String slotType = settings["page${page}MixedSlot${slot}Type"] ?: ""
            if (slotType) {
                def dev = settings["page${page}MixedSlot${slot}Device_${slotType}"]
                if (dev) entries << [slot: slot, sType: slotType, dev: dev]
            }
        }
    } else if (pType == "thermostat") {
        def dev = settings["page${page}ThermostatDevice"]
        if (dev) entries << [slot: 1, sType: "thermostat", dev: dev]
    } else if (pType) {
        List devs = sortDevicesForPage(page, pageDevices(page))
        devs.eachWithIndex { dev, idx ->
            if (dev) entries << [slot: idx + 1, sType: pType, dev: dev]
        }
    }
    return entries
}

private List<Integer> getPageOrder() {
    int total = numberOfPages()
    List stored = state.pageOrder ?: []
    List<Integer> order = stored.findAll { it >= 1 && it <= total }.collect { it as int }
    (1..total).each { pg -> if (!order.contains(pg)) order << pg }
    return order
}

private List<Integer> activePageOrder() {
    return getPageOrder()
}

// ── Button handler ────────────────────────────────────────────────────────────

def appButtonHandler(String buttonName) {
    List<Integer> order = getPageOrder()
    boolean changed = false

    if (buttonName.startsWith("deletePage_")) {
        int srcPage = buttonName.replace("deletePage_", "").toInteger()
        int total = numberOfPages()

        for (int i = srcPage; i < total; i++) {
            int next = i + 1
            String nextType = settings["page${next}Type"] ?: ""
            if (nextType) {
                app.updateSetting("page${i}Type", [value: nextType, type: "enum"])
            } else {
                app.clearSetting("page${i}Type")
            }
            // Copy mixed add-slot flags if applicable
            for (int n = 2; n <= maxMixedSlots(); n++) {
                if (nextType == "mixed" && settings["page${next}MixedAddSlot${n}"]) {
                    app.updateSetting("page${i}MixedAddSlot${n}", [value: true, type: "bool"])
                } else {
                    app.clearSetting("page${i}MixedAddSlot${n}")
                }
            }
            state["prevType${i}"] = nextType
        }

        app.clearSetting("page${total}Type")
        app.clearSetting("page${total}Devices")
        for (int n = 2; n <= maxMixedSlots(); n++) {
            app.clearSetting("page${total}MixedAddSlot${n}")
        }
        state["prevType${total}"] = ""

        if (total >= 2) {
            app.updateSetting("addPage${total}", [value: false, type: "bool"])
        }

        state.pageOrder = null
        infoLog "[Dashboard] Deleted page at position ${srcPage}, shifted pages down, new total ${total - 1}"
        return
    }

    def slotDeleteMatch = buttonName =~ /^page(\d+)MixedDeleteSlot(\d+)$/
    if (slotDeleteMatch) {
        int pg      = slotDeleteMatch[0][1] as int
        int delSlot = slotDeleteMatch[0][2] as int
        int total   = mixedSlotCount(pg)

        for (int i = delSlot; i < total; i++) {
            copyMixedSlotSettings(pg, i + 1, i)
        }
        clearMixedSlotSettings(pg, total)
        if (total >= 2) {
            app.updateSetting("page${pg}MixedAddSlot${total}", [value: false, type: "bool"])
        }
        infoLog "[Dashboard] Deleted mixed slot ${delSlot} on page ${pg}, shifted down, new total ${total - 1}"
        return
    }

    def slotUpMatch = buttonName =~ /^page(\d+)MixedSlotUp(\d+)$/
    if (slotUpMatch) {
        int pg   = slotUpMatch[0][1] as int
        int slot = slotUpMatch[0][2] as int
        if (slot > 1) {
            swapMixedSlotSettings(pg, slot, slot - 1)
            infoLog "[Dashboard] Moved mixed slot ${slot} up on page ${pg}"
        }
        return
    }

    def slotDnMatch = buttonName =~ /^page(\d+)MixedSlotDn(\d+)$/
    if (slotDnMatch) {
        int pg   = slotDnMatch[0][1] as int
        int slot = slotDnMatch[0][2] as int
        int total = mixedSlotCount(pg)
        if (slot < total) {
            swapMixedSlotSettings(pg, slot, slot + 1)
            infoLog "[Dashboard] Moved mixed slot ${slot} down on page ${pg}"
        }
        return
    }

    if (buttonName.startsWith("moveUp_")) {
        int pos = buttonName.replace("moveUp_", "").toInteger()
        if (pos > 1 && pos <= order.size()) {
            int tmp = order[pos - 2]
            order[pos - 2] = order[pos - 1]
            order[pos - 1] = tmp
            changed = true
        }
    } else if (buttonName.startsWith("moveDn_")) {
        int pos = buttonName.replace("moveDn_", "").toInteger()
        if (pos >= 1 && pos < order.size()) {
            int tmp = order[pos - 1]
            order[pos - 1] = order[pos]
            order[pos] = tmp
            changed = true
        }
    }

    if (changed) {
        state.pageOrder = order
        infoLog "[Dashboard] Page order: ${order.collect { pageType(it) }.join(' -> ')}"
    }
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────

def installed() {
    infoLog "[Dashboard] App installed"
    initialize()
}

def updated() {
    infoLog "[Dashboard] App updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

private String layoutFingerprint() {
    List parts = []
    parts << (settings.indicatorDevice?.id?.toString() ?: "none")
    (2..maxPages()).each { n -> parts << (settings["addPage${n}"] ? "1" : "0") }
    (1..maxPages()).each { pg ->
        String pType = settings["page${pg}Type"] ?: ""
        parts << "p${pg}:${pType}"
        parts << "ord:${settings["page${pg}Order"] ?: pg}"
        if (pType == "mixed") {
            int n = mixedSlotCount(pg)
            parts << "slots:${n}"
            (1..n).each { slot ->
                String st = settings["page${pg}MixedSlot${slot}Type"] ?: ""
                if (st == "clock") {
                    parts << "s${slot}:clock:${settings["page${pg}MixedSlot${slot}ClockShowTime"]}:${settings["page${pg}MixedSlot${slot}ClockShowDate"]}:${settings["page${pg}MixedSlot${slot}ClockFormat"]}"
                } else {
                    def dev = settings["page${pg}MixedSlot${slot}Device_${st}"]
                    parts << "s${slot}:${st}:${dev?.id ?: ''}"
                    parts << "lbl${slot}:${settings["page${pg}MixedSlot${slot}Label"]}"
                    if (isNumericType(st)) {
                        parts << "thresh${slot}:${settings["page${pg}MixedSlot${slot}Low"]}:${settings["page${pg}MixedSlot${slot}High"]}"
                    }
                }
                if (st && !isTappableAlready(st)) {
                    parts << "tap${slot}:${settings["page${pg}MixedSlot${slot}TapEnabled"]}:${settings["page${pg}MixedSlot${slot}TapTarget"]}:${settings["page${pg}MixedSlot${slot}TapRevertSeconds"]}"
                }
            }
        } else if (pType && pType != "thermostat") {
            def devs = settings["page${pg}Devices_${pType}"]
            String devIds = devs ? (devs instanceof List ? devs.collect { it.id }.sort().join(",") : devs.id.toString()) : ""
            parts << "devs:${devIds}"
            int slotCnt = devs ? (devs instanceof List ? devs.size() : 1) : 0
            if (slotCnt > 0) (1..slotCnt).each { slot -> parts << "lbl${slot}:${settings["page${pg}SlotLabel${slot}"]}" }
            if (isNumericType(pType)) {
                parts << "thresh:${settings["page${pg}NumLow"]}:${settings["page${pg}NumHigh"]}"
            }
            if (!isTappableAlready(pType)) {
                parts << "tap:${settings["page${pg}TapEnabled"]}:${settings["page${pg}TapTarget"]}:${settings["page${pg}TapRevertSeconds"]}"
            }
        }
    }
    return parts.join("|")
}

def initialize() {
    if (!settings.indicatorDevice) {
        infoLog "[Dashboard] No indicator device selected"
        return
    }

    int total = numberOfPages()
    List<Integer> order = getPageOrder()
    state.pageOrder = order
    infoLog "[Dashboard] Display order: ${order.collect { pageType(it) }.join(' -> ')}"


    // Push grid layouts in display order
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String pType = pageType(srcPage)
        String grid
        if (pType == "mixed") {
            grid = nxnString(mixedSlotCount(srcPage))
        } else if (pType == "thermostat") {
            grid = "thermostat"
        } else {
            List devs = pageDevices(srcPage)
            grid = nxnString(devs.size())
        }
        try {
            settings.indicatorDevice.setPageGridLayout(dispPage, grid)
        } catch (Exception e) {
            infoLog "[Dashboard] WARN -- setPageGridLayout failed: ${e.message}"
        }
    }

    int activePages = activePageOrder().size()
    try {
        settings.indicatorDevice.setNumberOfPages(activePages)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- setNumberOfPages failed: ${e.message}"
    }

    // Build slot maps and subscribe devices
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String sType = pageType(srcPage)
        List devs = (sType == "mixed") ? [] : sortDevicesForPage(srcPage, pageDevices(srcPage))
        Map slotMap = buildSlotMap(srcPage, devs, sType)
        state["slotMap${dispPage}"] = slotMap
        state["pageType${dispPage}"] = sType
        subscribePageDevices(dispPage, srcPage, sType)
    }

    // Auto-switch-block is just a driver state flag -- push it immediately on
    // every save instead of waiting on the (possibly reboot-gated) full layout push.
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        pushPageAutoSwitchConfig(dispPage, srcPage)
    }

    subscribe(settings.indicatorDevice, "displayRebooted", displayRebootedHandler)
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)
    unsubscribe(thermostatTappedHandler)
    unsubscribe(lightTappedHandler)
    subscribe(settings.indicatorDevice, "lightTapped",      lightTappedHandler)
    subscribe(settings.indicatorDevice, "thermostatTapped", thermostatTappedHandler)
    subscribe(location, "mode", locationModeHandler)
    // Periodic sync fallback for thermostat pages
    unschedule("thermostatPeriodicSync")
    runEvery1Minute("thermostatPeriodicSync")

    String newFp = layoutFingerprint()
    boolean layoutChanged = (newFp != state.layoutFingerprint)
    state.layoutFingerprint = newFp

    if (layoutChanged) {
        String mqttSt = settings.indicatorDevice.currentValue("mqttStatus") ?: ""
        if (mqttSt.startsWith("Connected")) {
            infoLog "[Dashboard] Layout changed -- rebooting display"
            try { settings.indicatorDevice.rebootDisplay() } catch (Exception e) { infoLog "[Dashboard] WARN -- rebootDisplay: ${e.message}" }
            runIn(35, "pushSlotTypesAndLayouts")
        } else {
            runIn(2, "pushSlotTypesAndLayouts")
        }
    } else {
        infoLog "[Dashboard] No layout change -- skipping reboot"
    }

    unschedule("syncLightStates")
    int syncMins = (settings.lightSyncInterval ?: "10") as int
    if (syncMins > 0) schedule("0 */${syncMins} * ? * *", syncLightStates)

    infoLog "[Dashboard] Initialized -- ${total} page(s)"
    // Push correct setpoints to all thermostat pages based on current mode
    runIn(5, "runAutoControlAllPages")
}

private void subscribePageDevices(int dispPage, int srcPage, String pType) {
    List<Map> entries = pageSlotEntries(srcPage)
    entries.each { e ->
        def dev = e.dev
        String sType = e.sType
        if (!dev) return
        switch (sType) {
            case "smoke":   subscribe(dev, "smoke",   smokeHandler);   break
            case "motion":  subscribe(dev, "motion",  motionHandler);  break
            case "water":   subscribe(dev, "water",   waterHandler);   break
            case "door":    subscribe(dev, "contact", doorHandler);    break
            case "window":  subscribe(dev, "contact", windowHandler);  break
            case "contact": subscribe(dev, "contact", contactHandler); break
            case "lock":    subscribe(dev, "lock",    lockHandler);    break
            case "garage":  subscribe(dev, "door",    garageHandler);  break
            case "light":   subscribe(dev, "switch",  lightHandler);       break
            case "temperature":  subscribe(dev, "temperature", temperatureHandler);  break
            case "humidity":     subscribe(dev, "humidity",    humidityHandler);     break
            case "illumination": subscribe(dev, "illuminance", illuminationHandler); break
            case "weather":      subscribe(dev, "temperature", weatherHandler);      break
            case "thermostat":
                subscribe(dev, "temperature",          thermostatHandler)
                subscribe(dev, "heatingSetpoint",      thermostatHandler)
                subscribe(dev, "coolingSetpoint",      thermostatHandler)
                subscribe(dev, "thermostatMode",       thermostatHandler)
                subscribe(dev, "thermostatOperatingState", thermostatHandler)
                // Separate temp sensor if configured for this page
                def tSensor = settings["page${dispPage}ThermostatTempSensor"]
                if (tSensor) subscribe(tSensor, "temperature", thermostatHandler)
                break
        }
    }
}

// ── Push layout ───────────────────────────────────────────────────────────────

def pushSlotTypesAndLayouts() {
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String sType = pageType(srcPage)
        List devs = (sType == "mixed") ? [] : sortDevicesForPage(srcPage, pageDevices(srcPage))
        pushPageSlotTypes(dispPage, srcPage, devs, sType)
        pauseExecution(100)
        pushPageLabels(dispPage, srcPage, devs, sType)
        pauseExecution(100)
        pushPageClockConfig(dispPage, srcPage, sType)
        pauseExecution(100)
        pushPageTapConfig(dispPage, srcPage, sType)
        pauseExecution(100)
    }

    pauseExecution(500)
    int activePages = activePageOrder().size()
    try { settings.indicatorDevice.setNumberOfPages(activePages) } catch (Exception e) { }
    pauseExecution(500)

    // Register thermostat device IDs so driver can handle taps directly (no subscription needed)
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) == "thermostat") {
            def dev = settings["page${srcPage}ThermostatDevice"]
            if (dev) infoLog "[Dashboard] Registered thermostat device ${dev.displayName} for page ${dispPage}"
        }
    }

    state.appPushInProgress = true
    try {
        settings.indicatorDevice.pushAllLayouts(activePages)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- pushAllLayouts failed: ${e.message}"
    }
}

private void pushPageSlotTypes(int dispPage, int srcPage, List devices, String sType) {
    Map types = [:]
    if (sType == "mixed") {
        int addedSlots = mixedSlotCount(srcPage)
        int totalSlots = nxnSlots(addedSlots)
        (1..totalSlots).each { slot ->
            String slotType = (slot <= addedSlots) ? (settings["page${srcPage}MixedSlot${slot}Type"] ?: "") : ""
            types[slot] = slotType ?: "none"
        }
    } else if (sType == "thermostat") {
        // Slot 1 = thermostat display, slots 2-4 = thermostat control buttons
        (1..4).each { slot -> types[slot] = "thermostat" }
    } else {
        int totalSlots = nxnSlots(devices.size())
        (1..totalSlots).each { slot ->
            types[slot] = (slot <= devices.size()) ? sType : "none"
        }
    }
    if (!types) return
    try {
        settings.indicatorDevice.updatePageSlotTypes(dispPage, types)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- updatePageSlotTypes failed: ${e.message}"
    }
}

private void pushPageLabels(int dispPage, int srcPage, List devices, String sType) {
    if (sType == "thermostat") return   // thermostat manages its own display
    Map labels = [:]
    if (sType == "mixed") {
        int addedSlots = mixedSlotCount(srcPage)
        String _autoGrid = nxnString(addedSlots)
        int _gridCols = _autoGrid.contains("x") ? (_autoGrid.split("x")[0] as int) : (_autoGrid as int)
        int maxChars = maxCharsForGrid(_gridCols)
        (1..addedSlots).each { slot ->
            String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
            if (slotType == "weather") {
                // Weather shows all device data instead of a name label -- clear any stale label
                labels[slot] = ""
            } else if (slotType) {
                def dev = settings["page${srcPage}MixedSlot${slot}Device_${slotType}"]
                if (dev) {
                    String custom = (settings["page${srcPage}MixedSlot${slot}Label"] ?: "").trim()
                    String name = custom ?: stripEmoji(dev.displayName ?: "")
                    if (name) labels[slot] = wrapLabel(name, maxChars)
                }
            }
        }
    } else if (sType == "weather") {
        // Weather shows all device data instead of a name label -- clear any stale label
        (1..(devices?.size() ?: 0)).each { slot -> labels[slot] = "" }
    } else {
        if (!devices) return
        String _autoGrid = nxnString(devices.size())
        int _autoCols = _autoGrid.contains("x") ? (_autoGrid.split("x")[0] as int) : (_autoGrid as int)
        int maxChars = maxCharsForGrid(_autoCols)
        devices.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot = idx + 1
            String custom = (settings["page${srcPage}SlotLabel${slot}"] ?: "").trim()
            String name = custom ?: stripEmoji(dev.displayName ?: "")
            if (name) labels[slot] = wrapLabel(name, maxChars)
        }
    }
    if (!labels) return
    try {
        settings.indicatorDevice.updatePageLabels(dispPage, labels)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- updatePageLabels failed: ${e.message}"
    }
}

// Clock is Mixed-slot only, no device -- driver ticks its own per-minute
// schedule, we just register which slots are clocks and how to render them.
private void pushPageClockConfig(int dispPage, int srcPage, String sType) {
    if (sType != "mixed") return
    Map cfg = [:]
    int addedSlots = mixedSlotCount(srcPage)
    (1..addedSlots).each { slot ->
        String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
        if (slotType == "clock") {
            boolean showTime = settings["page${srcPage}MixedSlot${slot}ClockShowTime"] != false
            boolean showDate = settings["page${srcPage}MixedSlot${slot}ClockShowDate"] != false
            String fmt = settings["page${srcPage}MixedSlot${slot}ClockFormat"] ?: "12"
            cfg[slot] = [showTime: showTime, showDate: showDate, format: fmt]
        }
    }
    if (!cfg) return
    try {
        settings.indicatorDevice.updatePageClockConfig(dispPage, cfg)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- updatePageClockConfig failed: ${e.message}"
    }
}

// Tap-to-navigate: any type not already tappable (light/lock/garage toggle;
// thermostat has its own tap controls) can be configured to jump to another
// page on tap, then revert back after a delay. The target is stored as a
// SOURCE page number (stable across reordering) and resolved to the current
// display position here, at push time.
private void pushPageTapConfig(int dispPage, int srcPage, String sType) {
    Map cfg = [:]
    List<Integer> ord = getPageOrder()

    if (sType == "mixed") {
        int addedSlots = mixedSlotCount(srcPage)
        (1..addedSlots).each { slot ->
            String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
            if (!slotType || isTappableAlready(slotType)) return
            if (!settings["page${srcPage}MixedSlot${slot}TapEnabled"]) return
            Integer targetSrc = settings["page${srcPage}MixedSlot${slot}TapTarget"] as Integer
            if (targetSrc == null) return
            int targetDisp = ord.indexOf(targetSrc) + 1
            if (targetDisp < 1) return
            def rs = settings["page${srcPage}MixedSlot${slot}TapRevertSeconds"]
            int revert = (rs == null) ? 10 : (rs as int)
            cfg[slot] = [targetPage: targetDisp, revertSeconds: revert]
        }
    } else if (sType && !isTappableAlready(sType)) {
        if (settings["page${srcPage}TapEnabled"]) {
            Integer targetSrc = settings["page${srcPage}TapTarget"] as Integer
            if (targetSrc != null) {
                int targetDisp = ord.indexOf(targetSrc) + 1
                if (targetDisp >= 1) {
                    def rs = settings["page${srcPage}TapRevertSeconds"]
                    int revert = (rs == null) ? 10 : (rs as int)
                    int cnt = pageDeviceCount(srcPage)
                    (1..cnt).each { slot -> cfg[slot] = [targetPage: targetDisp, revertSeconds: revert] }
                }
            }
        }
    }

    try {
        settings.indicatorDevice.updatePageTapConfig(dispPage, cfg)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- updatePageTapConfig failed: ${e.message}"
    }
}

// Blocks the driver from jumping to this page on sensor activity -- the
// tile still updates in place. Independent of tap-to-navigate above.
private void pushPageAutoSwitchConfig(int dispPage, int srcPage) {
    try {
        settings.indicatorDevice.setPageBlockAutoSwitch(dispPage, settings["page${srcPage}BlockAutoSwitch"] == true)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- setPageBlockAutoSwitch failed: ${e.message}"
    }
}

// ── Event handlers ────────────────────────────────────────────────────────────

def lightHandler(evt) {
    handleEvent(evt, "light", "on")
}

def smokeHandler(evt) {
    handleEvent(evt, "smoke", "detected")
}

def motionHandler(evt) {
    handleEvent(evt, "motion", "active")
}

def waterHandler(evt) {
    handleEvent(evt, "water", "wet")
}

def doorHandler(evt) {
    handleEvent(evt, "door", "open")
}

def windowHandler(evt) {
    handleEvent(evt, "window", "open")
}

def contactHandler(evt) {
    handleEvent(evt, "contact", "open")
}

def lockHandler(evt) {
    handleEvent(evt, "lock", "unlocked")
}

def garageHandler(evt) {
    handleEvent(evt, "garage", "open")
}

def temperatureHandler(evt) {
    handleNumericEvent(evt, "temperature")
}

def humidityHandler(evt) {
    handleNumericEvent(evt, "humidity")
}

def illuminationHandler(evt) {
    handleNumericEvent(evt, "illumination")
}

def weatherHandler(evt) {
    handleNumericEvent(evt, "weather")
}

private void handleThermostatTapInApp(String tapValue) {
    List parts = tapValue?.split(",")
    if (!parts || parts.size() < 5) { infoLog "[Dashboard] thermostatTap bad value: ${tapValue}"; return }
    int dispPage    = parts[0] as int
    int slot        = parts[1] as int
    String mode     = parts[2]
    BigDecimal heat = parts[3] as BigDecimal
    BigDecimal cool = parts[4] as BigDecimal

    List<Integer> order = getPageOrder()
    if (dispPage < 1 || dispPage > order.size()) return
    int srcPage = order[dispPage - 1]
    if (pageType(srcPage) != "thermostat") return
    def dev = settings["page${srcPage}ThermostatDevice"]
    if (!dev) { infoLog "[Dashboard] thermostatTap: no device for page ${srcPage}"; return }

    boolean away = (location.mode == "Away")

    // +/- disabled when away
    if (away && (slot == 2 || slot == 4)) {
        infoLog "[Dashboard] Away mode -- +/- taps ignored"
        return
    }

    infoLog "[Dashboard] Thermostat tap page ${dispPage} slot ${slot}: mode=${mode} heat=${heat} cool=${cool} away=${away}"

    // Use stored setpoints as the source of truth
    BigDecimal storedHeat = (state["page${srcPage}StoredHeat"] != null ? state["page${srcPage}StoredHeat"] : heat) as BigDecimal
    BigDecimal storedCool = (state["page${srcPage}StoredCool"] != null ? state["page${srcPage}StoredCool"] : cool) as BigDecimal

    switch (slot) {
        case 2:
            storedHeat = storedHeat + 1
            storedCool = storedCool + 1
            break
        case 3:
            List<String> cycle = ["cool", "off", "heat"]
            String liveMode = dev.currentValue("thermostatMode") ?: "off"
            int curIdx = cycle.indexOf(liveMode)
            if (curIdx < 0) curIdx = 0
            String next = cycle[(curIdx + 1) % cycle.size()]
            infoLog "[Dashboard] Mode: ${liveMode} -> ${next}"
            dev.setThermostatMode(next)
            state.pendingThermostatSyncPage = dispPage
            runIn(1, "runPendingThermostatSync")
            return
        case 4:
            storedHeat = storedHeat - 1
            storedCool = storedCool - 1
            break
    }

    // Store updated setpoints and push both to device
    state["page${srcPage}StoredHeat"] = storedHeat
    state["page${srcPage}StoredCool"] = storedCool
    infoLog "[Dashboard] Setpoints updated: heat=${storedHeat} cool=${storedCool}"
    dev.setHeatingSetpoint(storedHeat)
    dev.setCoolingSetpoint(storedCool)

    // Auto-set mode based on current temp vs new setpoints
    def tSensor = settings["page${srcPage}ThermostatTempSensor"]
    def tempVal = tSensor ? tSensor.currentValue("temperature") : dev.currentValue("temperature")
    if (tempVal != null) {
        BigDecimal currentTemp = tempVal as BigDecimal
        String currentMode = dev.currentValue("thermostatMode") ?: "off"
        String targetMode
        if (currentTemp > storedCool)      targetMode = "cool"
        else if (currentTemp < storedHeat) targetMode = "heat"
        else                               targetMode = "off"
        if (currentMode != targetMode) {
            infoLog "[Dashboard] Auto mode: temp=${currentTemp} -> ${targetMode}"
            dev.setThermostatMode(targetMode)
        }
    }

    state.pendingThermostatSyncPage = dispPage
    runIn(1, "runPendingThermostatSync")
}

def runPendingThermostatSync() {
    int dispPage = (state.pendingThermostatSyncPage ?: 0) as int
    if (dispPage < 1) return
    List<Integer> order = getPageOrder()
    if (dispPage > order.size()) return
    int srcPage = order[dispPage - 1]
    def dev = settings["page${srcPage}ThermostatDevice"]
    if (dev) syncThermostatDisplay(dispPage, dev)
}

def thermostatHandler(evt) {
    debugLog "[Dashboard] thermostatHandler: ${evt.device.displayName} ${evt.name}=${evt.value}"
    if (state.appPushInProgress) return
    String deviceId = evt.device.id.toString()
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) != "thermostat") return
        def dev = settings["page${srcPage}ThermostatDevice"]
        if (!dev || dev.id.toString() != deviceId) return
        syncThermostatDisplay(dispPage, dev)
        // Also re-evaluate auto-control on every temperature event so drift
        // across a setpoint boundary is caught immediately
        if (evt.name == "temperature") runAutoControlForPage(srcPage)
    }
}

def thermostatTappedHandler(evt) {
    List parts = evt.value?.split(",")
    if (parts?.size() == 5) handleThermostatTapInApp(evt.value)
}

private void syncThermostatDisplay(int dispPage, dev) {
    if (!dev) return
    List<Integer> order = getPageOrder()
    int srcPage = (dispPage <= order.size()) ? order[dispPage - 1] : dispPage

    // Use separate temp sensor if configured for this page
    def tSensor = settings["page${srcPage}ThermostatTempSensor"]
    String temp
    if (tSensor) {
        def sv = tSensor.currentValue("temperature")
        temp = sv != null ? sv.toString() : "--"
    } else {
        temp = (dev.currentValue("temperature") ?: "--").toString()
    }

    // Use stored setpoints if available (app-managed), else fall back to device values
    String heat  = (state["page${srcPage}StoredHeat"] ?: dev.currentValue("heatingSetpoint") ?: "--").toString()
    String cool  = (state["page${srcPage}StoredCool"] ?: dev.currentValue("coolingSetpoint") ?: "--").toString()
    String mode  = (dev.currentValue("thermostatMode") ?: "off").toString()
    String opSt  = (dev.currentValue("thermostatOperatingState") ?: "idle").toString()
    boolean away = (location.mode == "Away")

    infoLog "[Dashboard] Thermostat sync p${dispPage}: temp=${temp} heat=${heat} cool=${cool} mode=${mode} op=${opSt} away=${away}"
    Map data = [temp: temp, heatSetpoint: heat, coolSetpoint: cool, mode: mode, operatingState: opSt, away: away.toString()]
    try {
        settings.indicatorDevice.updateThermostatDisplay(dispPage, data)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- updateThermostatDisplay failed: ${e.message}"
    }
}

def thermostatPeriodicSync() {
    if (state.appPushInProgress) return
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) != "thermostat") return
        def dev = settings["page${srcPage}ThermostatDevice"]
        if (dev) {
            syncThermostatDisplay(dispPage, dev)
            // Re-evaluate auto-control every minute so drift is caught even
            // when no temperature events arrive (e.g. slow-polling devices)
            runAutoControlForPage(srcPage)
        }
    }
}

def locationModeHandler(evt) {
    infoLog "[Dashboard] Location mode changed: ${evt.value}"
    thermostatPeriodicSync()
    runAutoControlAllPages()
}

def runAutoControlAllPages() {
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        if (pageType(srcPage) == "thermostat") runAutoControlForPage(srcPage)
    }
}

private void runAutoControlForPage(int srcPage) {
    def dev = settings["page${srcPage}ThermostatDevice"]
    if (!dev) return

    boolean away = (location.mode == "Away")

    if (away) {
        BigDecimal awayHigh = getThermostatLimit(settings["page${srcPage}VarAwayHigh"])
        BigDecimal awayLow  = getThermostatLimit(settings["page${srcPage}VarAwayLow"])
        if (awayHigh == null || awayLow == null) {
            infoLog "[Dashboard] Auto-control p${srcPage}: away limits not set"
            return
        }

        // Store and push away setpoints
        state["page${srcPage}StoredHeat"] = awayLow
        state["page${srcPage}StoredCool"] = awayHigh
        BigDecimal curHeat = dev.currentValue("heatingSetpoint") as BigDecimal
        BigDecimal curCool = dev.currentValue("coolingSetpoint") as BigDecimal
        if (curHeat != awayLow)  { infoLog "[Dashboard] Away: setting heat setpoint ${curHeat} -> ${awayLow}";  dev.setHeatingSetpoint(awayLow) }
        if (curCool != awayHigh) { infoLog "[Dashboard] Away: setting cool setpoint ${curCool} -> ${awayHigh}"; dev.setCoolingSetpoint(awayHigh) }

        def tSensor = settings["page${srcPage}ThermostatTempSensor"]
        def tempVal = tSensor ? tSensor.currentValue("temperature") : dev.currentValue("temperature")
        if (tempVal == null) { infoLog "[Dashboard] Auto-control p${srcPage}: no temperature"; return }
        BigDecimal temp = tempVal as BigDecimal

        String curMode   = dev.currentValue("thermostatMode") ?: "off"
        String targetMode
        if (temp > awayHigh)     { targetMode = "cool"; infoLog "[Dashboard] Away: ${temp} > ${awayHigh} -- cooling" }
        else if (temp < awayLow) { targetMode = "heat"; infoLog "[Dashboard] Away: ${temp} < ${awayLow} -- heating" }
        else                     { targetMode = "off";  infoLog "[Dashboard] Away: ${temp} within [${awayLow}-${awayHigh}] -- off" }

        if (curMode != targetMode) {
            infoLog "[Dashboard] Auto-control p${srcPage}: ${curMode} -> ${targetMode}"
            dev.setThermostatMode(targetMode)
        }
    } else {
        // ── Home path ─────────────────────────────────────────────────────────
        // FIX v1.3.1: read current temp and set mode to keep within home limits,
        // matching the behaviour of the Away path. Previously only setpoints were
        // pushed and mode was never updated, causing temp to drift outside the band.
        BigDecimal hereHigh = getThermostatLimit(settings["page${srcPage}VarHereHigh"])
        BigDecimal hereLow  = getThermostatLimit(settings["page${srcPage}VarHereLow"])
        if (hereHigh == null || hereLow == null) {
            infoLog "[Dashboard] Auto-control p${srcPage}: home limits not set"
            return
        }

        BigDecimal curHeat = dev.currentValue("heatingSetpoint") as BigDecimal
        BigDecimal curCool = dev.currentValue("coolingSetpoint") as BigDecimal
        if (curHeat != hereLow)  { infoLog "[Dashboard] Home: setting heat setpoint ${curHeat} -> ${hereLow}";  dev.setHeatingSetpoint(hereLow) }
        if (curCool != hereHigh) { infoLog "[Dashboard] Home: setting cool setpoint ${curCool} -> ${hereHigh}"; dev.setCoolingSetpoint(hereHigh) }
        state["page${srcPage}StoredHeat"] = hereLow
        state["page${srcPage}StoredCool"] = hereHigh

        def tSensor = settings["page${srcPage}ThermostatTempSensor"]
        def tempVal = tSensor ? tSensor.currentValue("temperature") : dev.currentValue("temperature")
        if (tempVal == null) { infoLog "[Dashboard] Auto-control p${srcPage}: no temperature"; return }
        BigDecimal temp = tempVal as BigDecimal

        String curMode = dev.currentValue("thermostatMode") ?: "off"
        String targetMode
        if (temp > hereHigh)     { targetMode = "cool"; infoLog "[Dashboard] Home: ${temp} > ${hereHigh} -- cooling" }
        else if (temp < hereLow) { targetMode = "heat"; infoLog "[Dashboard] Home: ${temp} < ${hereLow} -- heating" }
        else                     { targetMode = "off";  infoLog "[Dashboard] Home: ${temp} within [${hereLow}-${hereHigh}] -- off" }

        if (curMode != targetMode) {
            infoLog "[Dashboard] Auto-control p${srcPage}: ${curMode} -> ${targetMode}"
            dev.setThermostatMode(targetMode)
        }
    }
}

private BigDecimal getThermostatLimit(val) {
    if (val == null || val == "") return null
    try { return val as BigDecimal } catch (Exception e) { return null }
}

private void handleEvent(evt, String sType, String activeValue) {
    if (state.appPushInProgress) {
        debugLog "[Dashboard] Skipping event during push: ${evt.displayName} ${evt.value}"
        return
    }

    String deviceId = evt.device.id.toString()
    List<Integer> order = activePageOrder()
    boolean found = false

    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String pType = pageType(srcPage)

        if (pType == "mixed") {
            // Search mixed slots for this device + matching type
            (1..mixedSlotCount(srcPage)).each { slot ->
                String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
                if (slotType != sType) return
                def dev = settings["page${srcPage}MixedSlot${slot}Device_${slotType}"]
                if (!dev || dev.id.toString() != deviceId) return
                found = true
                debugLog "Event p${dispPage}s${slot} ${sType} mixed (${evt.displayName}): ${evt.value}"
                if (evt.value == activeValue) {
                    settings.indicatorDevice.setPageMotionActive(dispPage, slot)
                } else {
                    settings.indicatorDevice.setPageMotionInactive(dispPage, slot)
                }
            }
        } else {
            if (pType != sType) return
            Map slotMap = state["slotMap${dispPage}"] ?: [:]
            int slot = (slotMap[deviceId] ?: 0) as int
            if (slot < 1) return
            found = true
            debugLog "Event p${dispPage}s${slot} ${sType} (${evt.displayName}): ${evt.value}"
            if (evt.value == activeValue) {
                settings.indicatorDevice.setPageMotionActive(dispPage, slot)
            } else {
                settings.indicatorDevice.setPageMotionInactive(dispPage, slot)
            }
        }
    }

    if (!found) infoLog "[Dashboard] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

// Temperature/humidity/illumination/weather -- pushes a formatted value +
// threshold pass/fail instead of a binary active/inactive icon match.
private void handleNumericEvent(evt, String sType) {
    if (state.appPushInProgress) {
        debugLog "[Dashboard] Skipping numeric event during push: ${evt.displayName} ${evt.value}"
        return
    }

    String deviceId = evt.device.id.toString()
    List<Integer> order = activePageOrder()
    boolean found = false

    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String pType = pageType(srcPage)

        if (pType == "mixed") {
            (1..mixedSlotCount(srcPage)).each { slot ->
                String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
                if (slotType != sType) return
                def dev = settings["page${srcPage}MixedSlot${slot}Device_${slotType}"]
                if (!dev || dev.id.toString() != deviceId) return
                found = true
                BigDecimal low  = getThermostatLimit(settings["page${srcPage}MixedSlot${slot}Low"])
                BigDecimal high = getThermostatLimit(settings["page${srcPage}MixedSlot${slot}High"])
                pushNumericSlotValue(dispPage, srcPage, slot, sType, evt.device, low, high)
            }
        } else {
            if (pType != sType) return
            Map slotMap = state["slotMap${dispPage}"] ?: [:]
            int slot = (slotMap[deviceId] ?: 0) as int
            if (slot < 1) return
            found = true
            BigDecimal low  = getThermostatLimit(settings["page${srcPage}NumLow"])
            BigDecimal high = getThermostatLimit(settings["page${srcPage}NumHigh"])
            pushNumericSlotValue(dispPage, srcPage, slot, sType, evt.device, low, high)
        }
    }

    if (!found) infoLog "[Dashboard] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

// Weather shows a curated summary (humidity/pressure/wind) instead of a
// single formatted value/unit, plus a condition icon, and never shows the
// device name as a label. Attribute names vary by community weather driver
// (e.g. OpenWeatherMap integrations), so each metric tries a few common
// candidate names and uses whichever the device actually reports.
private Map weatherAttrCandidates() {
    [
        humidity:      ["humidity"],
        pressure:      ["pressure", "atmosphericPressure", "barometricPressure"],
        windSpeed:     ["windSpeed", "wind_speed"],
        windDirection: ["windDirection", "wind_direction", "windBearing"],
        icon:          ["weatherIcons", "weatherIcon", "icon", "weather_icon"],
        condition:     ["weather", "weatherCondition", "conditionText", "description"]
    ]
}

private def firstAttrValue(dev, List names) {
    for (String n in names) {
        def v = dev.currentValue(n)
        if (v != null && v.toString() != "") return v
    }
    return null
}

// Converts a compass bearing in degrees to one of 8 points (360/8 = 45°
// per sector, N centered on 0° so its range is [337.5, 360) U [0, 22.5)).
// Passes through non-numeric values (some drivers already report e.g. "NW").
private String windDirLabel(val) {
    try {
        // Explicit double -- Math.round(BigDecimal) has no direct overload
        // and relying on implicit coercion silently fell through to the
        // catch block, returning the raw number instead of a compass point.
        double deg = (val as BigDecimal).doubleValue()
        List dirs = ["N","NE","E","SE","S","SW","W","NW"]
        int idx = Math.round((deg % 360) / 45) as int
        return dirs[idx % 8]
    } catch (Exception e) {
        return val.toString()
    }
}

// compact drops everything but the temperature -- used on grids with more
// than 4 tiles, where there's no room for the full humidity/pressure/wind
// block (see pushNumericSlotValue).
private String formatWeatherSummary(dev, tempValue, boolean compact = false) {
    if (compact) return (tempValue != null) ? "${tempValue}°" : "--"
    Map cands = weatherAttrCandidates()
    def humidity = firstAttrValue(dev, cands.humidity)
    def pressure = firstAttrValue(dev, cands.pressure)
    def windSpd  = firstAttrValue(dev, cands.windSpeed)
    def windDir  = firstAttrValue(dev, cands.windDirection)

    List lines = []
    if (tempValue != null) lines << "Temp: ${tempValue}°"
    if (humidity   != null) lines << "Humidity: ${humidity}%"
    if (pressure   != null) lines << "Pressure: ${pressure}"
    if (windSpd    != null) lines << "Wind Speed: ${windSpd}"
    if (windDir    != null) lines << "Wind Direction: ${windDirLabel(windDir)}"
    return lines ? lines.join("\n") : "--"
}

// Maps the device's icon code (OpenWeatherMap-style "01d".."50n") or, failing
// that, its text condition, to a normalized key. The driver owns the actual
// glyph codepoints (see weatherIconGlyph() there) -- this just picks a key.
private String weatherConditionKey(dev) {
    Map cands = weatherAttrCandidates()
    def iconCode = firstAttrValue(dev, cands.icon)
    if (iconCode) {
        String ic = iconCode.toString().toLowerCase()
        boolean night = ic.endsWith("n")
        String code2 = ic.length() >= 2 ? ic.substring(0, 2) : ic
        switch (code2) {
            case "01": return night ? "night" : "sunny"
            case "02": return night ? "night-partly-cloudy" : "partly-cloudy"
            case "03":
            case "04": return "cloudy"
            case "09": return "pouring"
            case "10": return "rainy"
            case "11": return "lightning-rainy"
            case "13": return "snowy"
            case "50": return "fog"
        }
    }
    def cond = firstAttrValue(dev, cands.condition)
    if (cond) {
        String c = cond.toString().toLowerCase()
        if (c.contains("thunder"))                                          return "lightning"
        if (c.contains("drizzle") || c.contains("rain"))                    return "rainy"
        if (c.contains("snow"))                                             return "snowy"
        if (c.contains("mist") || c.contains("fog") || c.contains("haze") ||
            c.contains("smoke") || c.contains("dust") || c.contains("sand")) return "fog"
        if (c.contains("wind") || c.contains("squall") || c.contains("tornado")) return "windy"
        if (c.contains("cloud"))                                            return "cloudy"
        if (c.contains("clear"))                                            return "sunny"
    }
    return "cloudy"
}

// srcPage is only needed to size the weather summary (full block on pages
// with 4 or fewer tiles, icon+temp only on denser grids) -- see
// pageDeviceCount/formatWeatherSummary.
private void pushNumericSlotValue(int dispPage, int srcPage, int slot, String sType, dev, BigDecimal low, BigDecimal high) {
    def value = dev?.currentValue(attributeFor(sType))
    String text
    String rangeState = "within"
    String iconKey = ""

    if (sType == "weather") {
        text = formatWeatherSummary(dev, value, pageDeviceCount(srcPage) > 4)
        iconKey = weatherConditionKey(dev)
    } else if (value == null) {
        text = "--"
    } else {
        try {
            text = "${(value as BigDecimal).stripTrailingZeros().toPlainString()}${unitFor(sType)}"
        } catch (Exception e) {
            text = "${value}${unitFor(sType)}"
        }
    }

    if (value != null) {
        try {
            BigDecimal v = value as BigDecimal
            if (low != null && v < low) rangeState = "below"
            else if (high != null && v > high) rangeState = "above"
        } catch (Exception e) { }
    }

    debugLog "[Dashboard] Numeric p${dispPage}s${slot} ${sType}: ${text} rangeState=${rangeState} iconKey=${iconKey}"
    try {
        settings.indicatorDevice.setPageSlotValue(dispPage, slot, text, rangeState, iconKey)
    } catch (Exception e) {
        infoLog "[Dashboard] WARN -- setPageSlotValue failed: ${e.message}"
    }
}

// ── Light tap handler ─────────────────────────────────────────────────────────

def lightTappedHandler(evt) {
    infoLog "[Dashboard] lightTappedHandler fired: ${evt.value}"
    List parts = evt.value?.split(",")
    if (!parts || parts.size() < 2) { infoLog "[Dashboard] WARN bad lightTapped value"; return }
    int dispPage = parts[0] as int
    int slot = parts[1] as int
    List<Integer> order = getPageOrder()
    if (dispPage < 1 || dispPage > order.size()) { infoLog "[Dashboard] WARN dispPage out of range"; return }

    int srcPage = order[dispPage - 1]
    String pType = pageType(srcPage)

    def dev = null
    String effectiveType = pType

    if (pType == "mixed") {
        String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
        effectiveType = slotType
        dev = settings["page${srcPage}MixedSlot${slot}Device_${slotType}"]
    } else {
        List devs = sortDevicesForPage(srcPage, pageDevices(srcPage))
        if (slot < 1 || slot > (devs?.size() ?: 0)) { infoLog "[Dashboard] WARN slot out of range"; return }
        dev = devs[slot - 1]
    }

    if (!dev) { infoLog "[Dashboard] WARN dev is null for tap p${dispPage}s${slot}"; return }

    switch (effectiveType) {
        case "light":
            String curState = dev.currentValue("switch") ?: "off"
            infoLog "[Dashboard] Toggling light: ${dev.displayName} currently ${curState}"
            if (curState == "on") { dev.off() } else { dev.on() }
            break
        case "lock":
            String curLock = dev.currentValue("lock") ?: "locked"
            infoLog "[Dashboard] Toggling lock: ${dev.displayName} currently ${curLock}"
            // Treat anything other than "locked" as unlocked (covers "unlocking", "unknown" states)
            if (curLock == "locked") { dev.unlock() } else { dev.lock() }
            break
        case "garage":
            String curDoor = dev.currentValue("door") ?: "closed"
            infoLog "[Dashboard] Toggling garage: ${dev.displayName} currently ${curDoor}"
            if (curDoor == "open") { dev.close() } else { dev.open() }
            break
        default:
            infoLog "[Dashboard] WARN tap on non-tappable type: ${effectiveType}"
    }
}

// ── Display reboot / push complete ───────────────────────────────────────────

def layoutPushCompleteHandler(evt) {
    state.appPushInProgress = false
    infoLog "[Dashboard] layoutPushCompleteHandler fired -- calling syncAllSensors"
    syncAllSensors()
    runIn(3, "runAutoControlAllPages")
}

def displayRebootedHandler(evt) {
    state.appPushInProgress = false
    infoLog "[Dashboard] Display rebooted -- repushing everything"
    // Re-subscribe in case subscriptions were lost
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)
    subscribe(settings.indicatorDevice, "thermostatTapped",   thermostatTappedHandler)
    infoLog "[Dashboard] Subscriptions re-established"
    subscribe(settings.indicatorDevice, "lightTapped",        lightTappedHandler)
    List<Integer> ord = activePageOrder()
    state.pageOrder = ord
    ord.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String pType = pageType(srcPage)
        String grid
        if (pType == "mixed") {
            grid = nxnString(mixedSlotCount(srcPage))
        } else if (pType == "thermostat") {
            grid = "thermostat"
        } else {
            List devs = pageDevices(srcPage)
            grid = nxnString(devs.size())
        }
        try { settings.indicatorDevice.setPageGridLayout(dispPage, grid) } catch (Exception e) { }
    }
    try { settings.indicatorDevice.setNumberOfPages(getPageOrder().size()) } catch (Exception e) { }
    runIn(2, "pushSlotTypesAndLayouts")
}

// ── State sync ────────────────────────────────────────────────────────────────

def syncLightStates() {
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        String pType = pageType(srcPage)
        if (pType == "mixed") {
            (1..mixedSlotCount(srcPage)).each { slot ->
                String slotType = settings["page${srcPage}MixedSlot${slot}Type"] ?: ""
                if (slotType != "light") return
                def dev = settings["page${srcPage}MixedSlot${slot}Device_${slotType}"]
                if (!dev) return
                String cur = dev.currentValue("switch") ?: "off"
                if (cur == "on") {
                    settings.indicatorDevice.setPageMotionActive(dispPage, slot)
                } else {
                    settings.indicatorDevice.setPageMotionInactive(dispPage, slot)
                }
                pauseExecution(30)
            }
        } else if (pType == "light") {
            List devs = sortDevicesForPage(srcPage, pageDevices(srcPage))
            devs.eachWithIndex { dev, idx ->
                if (!dev) return
                int slot = idx + 1
                String cur = dev.currentValue("switch") ?: "off"
                if (cur == "on") {
                    settings.indicatorDevice.setPageMotionActive(dispPage, slot)
                } else {
                    settings.indicatorDevice.setPageMotionInactive(dispPage, slot)
                }
                pauseExecution(30)
            }
        }
    }
}

def syncAllSensors() {
    infoLog "[Dashboard] Syncing all sensor states (appPushInProgress=${state.appPushInProgress})"
    state.appPushInProgress = false
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List<Map> entries = pageSlotEntries(srcPage)
        String pType = pageType(srcPage)

        // For single-type pages, also clear empty trailing slots
        int totalSlots = 0
        if (pType != "mixed") {
            List devs = sortDevicesForPage(srcPage, pageDevices(srcPage))
            totalSlots = nxnSlots(devs.size())
        }

        entries.each { e ->
            def dev = e.dev
            String sType = e.sType
            int slot = e.slot
            if (!dev) return
            if (sType == "thermostat") {
                syncThermostatDisplay(dispPage, dev)
                return
            }
            if (isNumericType(sType)) {
                BigDecimal low, high
                if (pType == "mixed") {
                    low  = getThermostatLimit(settings["page${srcPage}MixedSlot${slot}Low"])
                    high = getThermostatLimit(settings["page${srcPage}MixedSlot${slot}High"])
                } else {
                    low  = getThermostatLimit(settings["page${srcPage}NumLow"])
                    high = getThermostatLimit(settings["page${srcPage}NumHigh"])
                }
                pushNumericSlotValue(dispPage, srcPage, slot, sType, dev, low, high)
                pauseExecution(40)
                return
            }
            String attr = attributeFor(sType)
            String actVal = activeValueFor(sType)
            String cur = dev.currentValue(attr) ?: ""
            debugLog "Sync p${dispPage}s${slot} ${sType} (${dev.displayName}) = ${cur}"
            if (cur == actVal) {
                settings.indicatorDevice.setPageMotionActive(dispPage, slot)
            } else {
                settings.indicatorDevice.setPageMotionInactive(dispPage, slot)
            }
            pauseExecution(40)
        }

        if (pType != "mixed" && pType != "thermostat" && entries.size() < totalSlots) {
            ((entries.size() + 1)..totalSlots).each { slot ->
                settings.indicatorDevice.setPageSlotEmpty(dispPage, slot)
                pauseExecution(30)
            }
        }
    }
}

// ── Grid / label helpers ──────────────────────────────────────────────────────


private String nxnString(int count) {
    if (count <= 1)  return "1x1"
    if (count <= 2)  return "2x1"
    if (count <= 4)  return "2x2"
    if (count <= 6)  return "3x2"
    if (count <= 9)  return "3x3"
    if (count <= 12) return "4x3"
    if (count <= 16) return "4x4"
    if (count <= 20) return "5x4"
    if (count <= 25) return "5x5"
    return "6x5"
}

private int nxnSlots(int count) {
    if (count <= 1)  return 1
    if (count <= 2)  return 2
    if (count <= 4)  return 4
    if (count <= 6)  return 6
    if (count <= 9)  return 9
    if (count <= 12) return 12
    if (count <= 16) return 16
    if (count <= 20) return 20
    if (count <= 25) return 25
    return 30
}

private int maxCharsForGrid(int n) {
    switch (n) {
        case 1: return 30
        case 2: return 16
        case 3: return 11
        case 4: return 7
        default: return 6
    }
}

private String stripEmoji(String text) {
    if (!text) return ""
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}


private List sortDevicesByName(List devices) {
    if (!devices) return []
    return devices.findAll { it != null }
        .sort { a, b -> stripEmoji(a.displayName ?: "").compareToIgnoreCase(stripEmoji(b.displayName ?: "")) }
}

// Returns devices in slot-position order for a single-type page.
// If the user has assigned slot positions via page${p}SlotPos${devId}, those are used.
// Ties and unset positions fall back to alphabetical order.
private List sortDevicesForPage(int page, List devices) {
    if (!devices) return []
    List valid = devices.findAll { it != null }
    // Check whether any position overrides exist for this page
    boolean hasOverrides = valid.any { dev ->
        settings["page${page}SlotPos${dev.id}"] != null
    }
    if (!hasOverrides) return sortDevicesByName(valid)
    // Sort by user-assigned position, then alphabetically as tiebreaker
    return valid.sort { a, b ->
        int posA = (settings["page${page}SlotPos${a.id}"] ?: 9999) as int
        int posB = (settings["page${page}SlotPos${b.id}"] ?: 9999) as int
        if (posA != posB) return posA <=> posB
        return stripEmoji(a.displayName ?: "").compareToIgnoreCase(stripEmoji(b.displayName ?: ""))
    }
}

private String wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

// ── Logging ───────────────────────────────────────────────────────────────────

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
