#!/usr/bin/env python3
"""Offline source/manifest tests; optional Java policy tests. Never installs."""
import argparse
import ast
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import mock_open, patch

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/net/markandrew/kiosk/music"
spec = importlib.util.spec_from_file_location("plugin_build", ROOT / "tools/build.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class SourceChecks(unittest.TestCase):
    def test_icon_path_is_relative_to_host_assets_directory(self):
        data = builder.manifest(ROOT / "kiosk-satellite-plugin.json")
        asset_root = ROOT / "assets"
        resolved = asset_root / data["icon"]
        self.assertEqual(asset_root / "icon.png", resolved)
        self.assertTrue(resolved.is_file())
        self.assertFalse((asset_root / "assets" / "icon.png").exists())
        self.assertEqual("icon.png", data["icon"])

    def test_release_ui_and_remote_setup_preserve_write_boundaries(self):
        remote=(SOURCE / "RemoteSetup.java").read_text()
        for forbidden in ['saveSettings(', 'updateSettings(', '.mutate(', '.write(', 'pair_web_player']:
            self.assertNotIn(forbidden,remote)
        self.assertIn('catch(RuntimeException|LinkageError',remote)
        self.assertIn('settings.cancel',remote)
        self.assertIn('new CancellationException()',remote)
        loader=(SOURCE / 'ArtworkLoader.java').read_text()
        self.assertIn('if(!external)connection.setRequestProperty(session.header, session.credential)',loader)
        self.assertIn('setInstanceFollowRedirects(false)',loader)
        display=(SOURCE / 'MusicApi.java').read_text().split('void annotateDisplayVolume(',1)[1].split('/** Only the exact',1)[0]
        self.assertNotIn('mutate(',display)
        self.assertIn('ids.length()>64',display)
        self.assertIn('System.nanoTime()>deadline',display)

    def test_shared_private_players_follow_hide_flag_and_naming_is_read_only(self):
        api = (SOURCE / "MusicApi.java").read_text()
        visibility = api.split('OwnReceiverPolicy.selectable(', 1)[1].split('PlayerPolicy.reference(', 1)[0]
        self.assertNotIn('optBoolean("private"', visibility)
        self.assertIn('optBoolean("hide_in_ui"', visibility)
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        self.assertIn('KioskNameAccess.read(host, apply)', plugin)
        self.assertIn('receiverNameEpoch != ticket', plugin)
        self.assertIn('kioskNames.remember(kioskName)', plugin)
        self.assertIn('if(kioskName.isEmpty()&&answered.get())return', plugin)
        self.assertIn('apply.accept(""), 5000', plugin)
        self.assertIn('ReceiverNamePolicy.override(draft.get("receiverName"))', plugin)
        feature = (SOURCE / "FeatureController.java").read_text().split('void visibility(', 1)[1].split('interface Work', 1)[0]
        self.assertIn('"players/all"', feature)
        self.assertNotIn('.write(', feature)
        self.assertIn('VisibilityDiagnostics.summary(rows, receiverId)', feature)

    def test_own_receiver_visibility_is_injected_in_all_playback_paths(self):
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        for target in ['playbackApi', 'setupController', 'nowPlayingController', 'features']:
            self.assertIn(target + '.ownReceiver(this::ownReceiverId)', plugin)
        api = (SOURCE / "MusicApi.java").read_text()
        self.assertIn('OwnReceiverPolicy.selectable(id, p.optString("type"), ownId', api)
        self.assertEqual(5, api.count('ownReceiver.apply(session)'))
        self.assertIn('addon, session.addon', plugin)
        self.assertIn('local.state()', plugin)

    def test_receiver_registration_separates_identity_namespaces_and_confirms_device(self):
        code = (SOURCE / "ReceiverRegistration.java").read_text()
        self.assertIn('verify(session, maInstanceId, maUserId, current)', code)
        self.assertIn('maUserId.equals(((JSONObject)api.identity(session))', code)
        self.assertIn('maInstanceId.equals(api.serverInfo(session)', code)
        self.assertNotIn('serverId.equals(api.serverInfo(session)', code)
        self.assertIn('local.claimAutoPair(clientId, transportId)', code)
        self.assertIn('local.privateToken(clientId, transportId)', code)
        self.assertIn('new AppPairingFlow(clientId', code)
        self.assertIn('generation != ticket', code)
        self.assertIn('awaiting server activation', code)
        self.assertIn('local.state().get("active")', code)

    def test_app_pairing_uses_normal_user_scope_and_preserves_diagnostic_mode(self):
        code = (SOURCE / "ReceiverRegistration.java").read_text()
        self.assertIn('private boolean diagnosticsOnly;', code)
        self.assertIn('final boolean attemptPairing = !diagnosticsOnly; diagnosticsOnly = false', code)
        self.assertIn('diagnosticsOnly = true', code.split('void refreshDiagnostics()', 1)[1])
        self.assertLess(code.index('if (!attemptPairing)'), code.index('new AppPairingFlow'))
        self.assertIn('PairingDiagnostics.grant(user, scopes, "players.control")', code)
        self.assertNotIn('"config/players/setup"', code)
        self.assertNotIn('"config/flows/submit"', code)
        self.assertEqual(1, code.count('api.mutate('))
        self.assertIn('"sendspin/pair_web_player", values, current', code)
        self.assertIn('values.remove("pairing_token")', code)
        self.assertIn('grant != PairingDiagnostics.Grant.GRANTED', code)
        self.assertIn('advanced.add("Check connection"', (SOURCE / "SetupController.java").read_text())
        connection=(SOURCE / "MusicApi.java").read_text().split('PlayerPolicy.Snapshot connectionSnapshot(',1)[1].split('static PlayerPolicy.Snapshot parseSnapshot',1)[0]
        self.assertNotIn('mutate(',connection)
        self.assertNotIn('pair_web_player',connection)
        hello = (ROOT / 'third_party/sendspindroid/android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageBuilder.kt').read_text()
        self.assertIn('put("product_name", "Mobile Application")', hello)
        self.assertIn('put("manufacturer", manufacturer)', hello)

    def test_manifest(self):
        value = builder.manifest(ROOT / "kiosk-satellite-plugin.json")
        self.assertIn("host.control", value["capabilities"])
        self.assertEqual(["standaloneToken"], [item["key"] for item in value["settings"] if item["type"] == "secret"])
        group = next(item for item in value["settings"] if item["key"] == "hardwareGroupVolume")
        self.assertEqual("boolean", group["type"])
        self.assertIs(False, group["default"])
        self.assertTrue((SOURCE / "MusicAssistantPlugin.java").is_file())

    def test_setting_conditions_reject_original_forward_reference(self):
        settings = json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["settings"]
        builder.validate_settings(settings)
        original_order = [s for s in settings if s["key"] != "defaultPlayer"] + [s for s in settings if s["key"] == "defaultPlayer"]
        with self.assertRaisesRegex(ValueError, "earlier"):
            builder.validate_settings(original_order)

    def test_invalid_setting_conditions_and_defaults(self):
        toggle = {"key": "advanced", "title": "Advanced", "type": "boolean", "default": False}
        child = {"key": "child", "title": "Child", "type": "string", "default": ""}
        for condition in [None, {"key": "missing", "equals": True}, {"key": "child", "equals": True},
                          {"key": "advanced", "equals": True, "notEmpty": True},
                          {"key": "advanced", "notEmpty": False}, {"key": "advanced", "equals": []},
                          {"key": "advanced", "unknown": True}]:
            with self.subTest(condition=condition), self.assertRaises(ValueError):
                builder.validate_settings([toggle, dict(child, visibleWhen=condition)])
        for default in [None, "false", 0]:
            with self.subTest(default=default), self.assertRaises(ValueError):
                builder.validate_settings([dict(toggle, default=default)])
        for condition in [{"key": "advanced", "equals": True}, {"key": "advanced", "notEquals": [False]},
                          {"key": "advanced", "notEmpty": True}]:
            builder.validate_settings([toggle, dict(child, visibleWhen=condition)])

    def test_generic_plugin_identity(self):
        code = "\n".join(file.read_text() for file in SOURCE.glob("*.java"))
        for legacy in ["EchoMusicPlugin", "EchoMusicBrowser", "echo-browser", "echo-artwork", "amzn echo show 5"]:
            self.assertNotIn(legacy, code)
        self.assertIn("class MusicAssistantPlugin", code)

    def test_no_privileged_features(self):
        code = "\n".join(file.read_text() for file in SOURCE.glob("*.java"))
        for forbidden in ["setSSLSocketFactory", "setHostnameVerifier", "CookieManager", "CookieHandler", "SharedPreferences", "getDeclaredField", "auth/long_lived_access_token", "host.log(", "System.out", "printStackTrace"]:
            self.assertNotIn(forbidden, code)
        self.assertIn("setInstanceFollowRedirects(false)", code)
        self.assertIn("MAX_RESPONSE", code)
        self.assertIn("IngressPolicy.command(command)", code)

    def test_explicit_single_shot_playback(self):
        api = (SOURCE / "MusicApi.java").read_text()
        self.assertEqual(1, api.count('send(session, "player_queues/play_media"'))
        self.assertIn("IngressPolicy.playback(uri, option)", api)
        self.assertIn('fresh.queue(playerId)', api)
        self.assertIn('fresh.externalSource(playerId)', api)
        self.assertEqual(1, api.count('send(session, "player_queues/transfer"'))
        self.assertIn('fresh.validateTransfer(source, target, expectedQueue)', api)
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        controller = (SOURCE / "PlayerController.java").read_text()
        self.assertIn("busy.compareAndSet(false, true)", controller)
        self.assertIn('enqueue ? "add" : "play"', plugin)
        self.assertIn("PlayerPolicy.immediate(option)", controller)

    def test_targeted_playback_avoids_full_lists(self):
        api = (SOURCE / "MusicApi.java").read_text()
        target = api.split("PlayerPolicy.Snapshot playbackSnapshot(", 1)[1].split("void transfer(", 1)[0]
        for forbidden in ['snapshot(session)', '"players/all"', '"player_queues/all"']:
            self.assertNotIn(forbidden, target)
        self.assertEqual(1, target.count('"players/get"'))
        self.assertEqual(1, target.count('"player_queues/get"'))
        self.assertIn("visited.size() > 8", target)
        self.assertIn("!queue.available", target)
        controller = (SOURCE / "PlayerController.java").read_text()
        self.assertIn('begin(true, (op, session, snapshot)', controller)
        self.assertNotIn("Checking players", controller)

    def test_player_bridge_and_settings(self):
        code = (SOURCE / "KioskPlayerBridge.java").read_text()
        self.assertIn('message.put("return_response", true)', code)
        self.assertIn('pick.same(observed.current)', code)
        self.assertIn('"media_player_list"', code)
        self.assertIn('"media_player_set"', code)
        controller = (SOURCE / "PlayerController.java").read_text()
        self.assertIn('DefaultPlayerPolicy.saved(settings)', controller)
        self.assertIn('active == this', controller)
        self.assertNotIn('"Confirm transfer"', controller)
        self.assertIn('() -> transfer(from.id, player.id, player.name, queue)', controller)
        self.assertNotIn('"Confirm replacement"', controller)
        self.assertIn('playback(item, option, shuffle, start);', controller)
        self.assertIn('begin(true, (op, session, snapshot)', controller)
        self.assertNotIn('"Use default"', controller)
        chooser=controller.split('private void listPlayers(',1)[1].split('void players(boolean transfer, Runnable after)',1)[0]
        for removed in ['"Group"','"Player settings"','"Plugin preferences"']:
            self.assertNotIn(removed,chooser)
        self.assertIn('panel.section("This device")',chooser)
        self.assertIn('panel.section("Other devices")',chooser)
        self.assertIn('api.transfer(session, source, target, queue, op::current)', controller)
        settings = json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["settings"]
        self.assertTrue({"playerMode", "defaultPlayer", "localPlayer"} <= {s["key"] for s in settings})
        self.assertNotIn("kioskNode",{s["key"] for s in settings})

    def test_setup_discovery_and_staged_save(self):
        settings = {s["key"]: s for s in json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["settings"]}
        self.assertEqual("Fixed default player", settings["playerMode"]["default"])
        self.assertEqual("", settings["addon"]["default"])
        self.assertEqual("", settings["defaultPlayer"]["default"])
        setup = (SOURCE / "SetupController.java").read_text()
        self.assertIn('panel.picker("Music Assistant server"', setup)
        self.assertIn('panel.picker("Default player"', setup)
        self.assertNotIn('panel.picker("This kiosk"', setup)
        self.assertNotIn('panel.picker("Local Sendspin player"', setup)
        self.assertIn('panel.formActions("Save"', setup)
        self.assertEqual(1, setup.count('ui.save(new HashMap<>(draft))'))
        self.assertNotIn('media_player_set', setup)
        self.assertNotIn('api.play(', setup)
        discovery = (SOURCE / "DiscoveryAccess.java").read_text()
        self.assertIn('"get_panels"', discovery)
        self.assertNotIn('"/addons"', discovery)
        self.assertIn('choices.size() == 1', discovery)
        self.assertIn('More than one MA instance', discovery)
        self.assertNotIn('call_service', discovery)

    def test_catalogue_read_is_scoped_bounded_and_setup_preserves_players(self):
        discovery = (SOURCE / "DiscoveryAccess.java").read_text()
        self.assertNotIn('"get_services"', discovery)
        self.assertIn('"auth/sign_path"', discovery)
        self.assertIn('message.put("expires", 30)', discovery)
        self.assertIn('origin.equals(latest)', discovery)
        self.assertIn('userId.equals', discovery)
        self.assertIn('ServiceCatalogue.ReadFailure', discovery)
        self.assertIn('DiscoveryPolicy.failure', discovery)
        catalogue = (SOURCE / "ServiceCatalogue.java").read_text()
        for required in ['setInstanceFollowRedirects(false)', 'setUseCaches(false)', 'setRequestMethod("GET")',
                         'MAX_CATALOGUE', 'setConnectTimeout(10000)', 'setReadTimeout(15000)', 'connection.disconnect()']:
            self.assertIn(required, catalogue)
        setup = (SOURCE / "SetupController.java").read_text()
        self.assertNotIn('addons -> discovery.nodes', setup)
        self.assertIn('Collections.emptyList(), snapshot, detectedLocal', setup)
        self.assertIn('discovery.cancel()', setup)

    def test_simple_default_setup(self):
        setup = (SOURCE / "SetupController.java").read_text()
        self.assertIn('void open() { open(defaults(ui.settings())); }', setup)
        self.assertIn('panel.disclosure("Diagnostics"', setup)
        self.assertIn('panel.picker("Default player"', setup)
        self.assertNotIn('panel.picker("Default mode"', setup)
        self.assertIn('DefaultPlayerPolicy.selected(snapshot,chosen)', setup)
        settings = {s["key"]: s for s in json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["settings"]}
        self.assertNotIn("visibleWhen", settings["defaultPlayer"])
        self.assertNotIn("advanced",settings)
        self.assertNotIn("kioskNode",settings)
        self.assertEqual({"key":"defaultPlayer","equals":""},settings["playerMode"]["visibleWhen"])
        self.assertEqual({"key":"playerMode","equals":"Follow Kiosk Satellite"},settings["localPlayer"]["visibleWhen"])
        self.assertTrue(all(0 < len(s["description"]) <= 400 for s in settings.values()))
        self.assertEqual([{"id": "toggle", "title": "Open"}],
                         json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["commands"])

    def test_default_path_has_no_kiosk_or_ip_discovery(self):
        setup = (SOURCE / "SetupController.java").read_text()
        controller = (SOURCE / "PlayerController.java").read_text()
        for code in [setup, controller]:
            for forbidden in ["discovery.nodes(", "discovery.node(", "discovery.deviceIp(", "uniqueLocal(", "bridge.", "NsdNodeResolver", "FollowPolicy."]:
                self.assertNotIn(forbidden, code)
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        self.assertNotIn("new NsdNodeResolver", plugin)
        self.assertIn('DefaultPlayerPolicy.selected(snapshot, temporary.isEmpty() ? fixed : temporary)', controller)
        self.assertIn('if (PlayerPolicy.immediate(option)) ui.playbackStarted()', controller)

    def test_bounded_native_artwork(self):
        code = (SOURCE / "ArtworkLoader.java").read_text()
        for value in ["setInstanceFollowRedirects(false)", "512 * 1024", "3 * 1024 * 1024", "4096", "inSampleSize", "ArrayBlockingQueue", 'session.base.toString() + "imageproxy"']:
            self.assertIn(value, code)

    def test_compact_landscape_structure(self):
        code = (SOURCE / "BrowserView.java").read_text()
        self.assertNotIn("LinearLayout searchRow", code)
        self.assertNotIn("LinearLayout bottom", code)
        self.assertIn("NativeStyle.button(button, theme, filled)", code)
        self.assertIn("button.setMinimumHeight(0)", (SOURCE / "NativeStyle.java").read_text())
        self.assertNotIn("LinearLayout footer", code)
        self.assertNotIn('button("Previous"', code)
        self.assertIn('"Load more"', code)
        self.assertIn('actions.more()', code)
        self.assertIn("loading.setVisibility(value.busy?VISIBLE:GONE)", code)
        self.assertIn("if(value.busy)return",code)
        self.assertIn("binding.image.setImageDrawable(null)", code)
        self.assertIn("scroll.setOnScrollChangeListener", code)
        self.assertIn("TouchLayout.columns(Math.max(1,dp-RAIL_WIDTH-NativeStyle.GAP),", code)
        self.assertIn("arrangeHeader(dp)", code)
        self.assertIn("class SquareArtwork", code)

    def test_touch_actions_and_search_alignment(self):
        code = (SOURCE / "BrowserView.java").read_text()
        self.assertIn("query.setGravity(Gravity.START | Gravity.CENTER_VERTICAL)", code)
        self.assertIn("query.setIncludeFontPadding(false)", code)
        self.assertIn("setOnLongClickListener", code)
        self.assertIn('button("⋮"', code)
        self.assertIn('case "open": actions.open(item)', code)
        self.assertIn('case "play": actions.play(item, false)', code)
        self.assertIn("actions.back()", code)
        self.assertIn("actions.play(value.parent, false)", code)
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        self.assertIn("history.addLast", plugin)
        self.assertIn("history.removeLast", plugin)
        self.assertIn("target.restoreScroll(position)", plugin)

    def test_native_player_keeps_browser(self):
        code = (SOURCE / "MusicAssistantPlugin.java").read_text()
        handoff = code.split("private void showNativePlayer()", 1)[1].split("@Override public synchronized void interaction()", 1)[0]
        self.assertIn('new NowPlayingController', handoff)
        self.assertNotIn('"showNowPlaying"', handoff)
        self.assertNotIn("close()", handoff)
        self.assertNotIn("hideOverlay", handoff)
        self.assertNotIn("stopBrowsing", handoff)

    def test_receiver_is_explicit_and_session_owned(self):
        code = (ROOT / "receiver/net/markandrew/kiosk/music/receiver/PluginReceiver.kt").read_text()
        self.assertNotIn(": Service", code)
        self.assertIn('ReceiverEndpoint.parse(endpoint)', code)
        self.assertIn('scope?.cancel()', code)
        self.assertIn('old?.destroy()', code)
        self.assertIn('unpairedAccessEnabled = false', (ROOT / "receiver/net/markandrew/kiosk/music/receiver/ReceiverStore.kt").read_text())
        store = (ROOT / "receiver/net/markandrew/kiosk/music/receiver/ReceiverStore.kt").read_text()
        self.assertIn('musicassistantplugin-receiver-v1.enc', store)
        self.assertNotIn('setStreamVolume', code)
        plugin = (SOURCE / 'MusicAssistantPlugin.java').read_text()
        startup = plugin.split('void start(', 1)[1].split('@Override', 1)[0]
        self.assertNotIn('receiver.start(', startup)
        self.assertIn('receiver.stop()', plugin)

    def test_now_playing_lifetime_and_write_guards(self):
        code = (SOURCE / 'NowPlayingController.java').read_text()
        for value in ['this::current', 'selected.equals(currentPlayer.get())', 'expected.queueId.equals',
                      'expected.itemId.equals', 'api.playbackSnapshot', 'api.mutate', 'worker.shutdownNow()', 'artwork.stop()']:
            self.assertIn(value, code)
        self.assertEqual(2, code.count('api.mutate('))
        self.assertIn('Volume level was not confirmed. Unmute was not sent.',code)
        self.assertIn('Group changed. Unmute was not sent.',code)
        view = (SOURCE / 'NowPlayingView.java').read_text()
        self.assertIn('NativeStyle.button', view)
        self.assertIn('NativeStyle.slider', view)
        self.assertIn('theme.styleSlider', (SOURCE / 'NativeStyle.java').read_text())
        self.assertIn('artwork.setBackground(null)', view)
        self.assertNotIn('artwork.setBackground(theme.card())', view)
        self.assertIn('new MusicPanelSurface', (SOURCE / 'NowPlayingSurface.java').read_text())
        home=(SOURCE/'MusicAssistantPlugin.java').read_text().split('if ("home".equals(destination)) {',1)[1].split('if ("library"',1)[0]
        self.assertIn('hideBrowser()',home)
        self.assertNotIn('executeCommand',home)

    def test_now_playing_panels_attach_and_refresh_does_not_drop_controls(self):
        plugin = (SOURCE / 'MusicAssistantPlugin.java').read_text()
        panel = plugin.split('private void showPanel(String title, java.util.function.Consumer<TouchPanel> populate, BooleanSupplier current)', 1)[1].split('@Override public void nowPlaying', 1)[0]
        self.assertIn('receiverMain.post', panel)
        self.assertNotIn('target.post', panel)
        self.assertIn('playerPanel = playback.panel(', panel)
        self.assertLess(panel.index('if (attached)'), panel.index('host.showOverlay'))
        controller = (SOURCE / 'NowPlayingController.java').read_text()
        action = controller.split('private void action(', 1)[1].split('void dismiss()', 1)[0]
        self.assertIn('work.beginWrite()', action)
        self.assertNotIn('|| reading', action)
        self.assertNotIn('removeCallbacksAndMessages', action)
        self.assertIn('view == null || work.writing()', controller)
        self.assertIn('surface == target', controller)

    def test_successful_play_resets_retained_browser_home(self):
        code = (SOURCE / "MusicAssistantPlugin.java").read_text()
        callback = code.split('public void playbackStarted()', 1)[1].split('public void panel(', 1)[0]
        for required in ['target.post(', 'playerEpoch != epoch', 'view != target', 'generation.get() != ticket',
                         'showNativePlayer();', 'target.clearSearch();', 'browse("albums", 0);']:
            self.assertIn(required, callback)
        self.assertLess(callback.index('showNativePlayer();'), callback.index('browse("albums", 0);'))
        browse = code.split('void browse(String category, int offset)', 1)[1].split('@Override', 1)[0]
        self.assertIn('restorePosition = 0', browse)
        self.assertIn('detail = null; history.clear()', browse)
        self.assertIn('Math.max(0, offset), ""', browse)
        controller = (SOURCE / "PlayerController.java").read_text()
        playback = controller.split('private void playback(', 1)[1].split('void nowPlaying()', 1)[0]
        self.assertLess(playback.index('api.play('), playback.index('ui.playbackStarted()'))
        self.assertIn('if (!op.current()) return;', playback)

    def test_combined_native_feature_guards(self):
        api = (SOURCE / "FeatureApi.java").read_text()
        for guard in ['static_group_members', 'can_group_with', 'index_in_buffer', 'samePlaylist(rows, expected)',
                      'provider_filter', 'tracks.length() < 500', 'recursive', 'library_sync_back', 'music/verify_item_uri']:
            self.assertIn(guard, api)
        controller = (SOURCE / "FeatureController.java").read_text()
        self.assertIn('no automatic retry was made', controller)
        self.assertIn('current.getAsBoolean()', api)
        plugin_code = (SOURCE / "MusicAssistantPlugin.java").read_text()
        self.assertNotIn('void tools()',plugin_code)
        self.assertIn('void createPlaylist()',plugin_code)
        self.assertIn('void joinGroup()',plugin_code)
        for destination in ['case "queue"', 'case "transfer"', 'case "group"', 'case "lyrics"']:
            self.assertIn(destination, plugin_code)
        for file in ['BrowserView.java', 'TouchPanel.java', 'ItemActionsView.java']:
            self.assertIn('NativeStyle.button', (SOURCE / file).read_text())
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        self.assertIn('session == previous.session', plugin)
        self.assertIn('publish(previous.page, ticket)', plugin)
        self.assertIn('retained.append(result)', plugin)

    def test_network_bounds_and_lifetime(self):
        api = (SOURCE / "MusicApi.java").read_text()
        for required in ["setConnectTimeout(10000)", "setReadTimeout(15000)", "connection.disconnect()", "Thread.currentThread().isInterrupted()"]:
            self.assertIn(required, api)
        plugin = (SOURCE / "MusicAssistantPlugin.java").read_text()
        for required in ["generation.get() == ticket", "old.cancel(true)", "old.shutdownNow()", "api.cancel()", "access.clear()"]:
            self.assertIn(required, plugin)

    def test_player_settings_and_local_join_separation(self):
        setup=(SOURCE/'SetupController.java').read_text()
        self.assertIn('ui.panel("Plugin preferences"',setup)
        self.assertNotIn('0.8.0-dev',setup)
        self.assertNotIn("follows Kiosk's own Media Player source",setup)
        plugin=(SOURCE/'MusicAssistantPlugin.java').read_text()
        self.assertNotIn('Show pairing token',plugin)
        feature=(SOURCE/'FeatureController.java').read_text()
        self.assertIn('String local=selected',feature)
        self.assertIn('if(moving)confirm(',feature)
        self.assertIn('No queue transfer or automatic retry',feature)
        self.assertIn('queueActionRow',feature)
        browser=(SOURCE/'BrowserView.java').read_text()
        self.assertNotIn('Library tools',browser)
        self.assertNotIn('navigation.addView(create',browser)
        self.assertNotIn('railSection("Tools")',browser)
        self.assertIn('void createPlaylist(JSONArray uris)',(SOURCE / 'FeatureController.java').read_text())
        self.assertNotIn('navigation.addView(join',browser)
        self.assertIn('navigation.addView(railSection("Settings"))',browser)
        self.assertIn('button("Plugin"',browser)
        self.assertIn('button("Library",()->{if(!sending)actions.librarySettings();}',browser)
        self.assertIn('navigation.addView(player',browser)
        self.assertNotIn('"Player settings"',browser)
        self.assertIn('panel.disclosure("Diagnostics"',setup)
        self.assertIn('panel.section("This device")',setup)
        self.assertNotIn('receiverSetup(',plugin)

    def test_sdk_lock_and_license(self):
        lock = json.loads((ROOT / "sdk.lock.json").read_text())
        self.assertEqual(40, len(lock["commit"]))
        self.assertEqual(7, len(lock["git_blobs"]))
        self.assertIn("sdk/src/me/jxl/kiosk/plugins/CameraFrame.java", lock["git_blobs"])
        self.assertEqual("Apache-2.0", lock["license"])
        self.assertEqual("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", builder.git_blob(b""))
        self.assertIn("MIT License", (ROOT / "LICENSE").read_text())

    def test_python_syntax(self):
        for file in (ROOT / "tools").glob("*.py"):
            ast.parse(file.read_text(), filename=str(file))

    def test_stable_release_identity_and_portable_build(self):
        data = builder.manifest(ROOT / 'kiosk-satellite-plugin.json')
        version = data['version']
        self.assertRegex(version, r'^\d+\.\d+\.\d+$')
        self.assertIn('Music Assistant · ' + version, (SOURCE / 'SetupController.java').read_text())
        receiver = (ROOT / 'receiver/net/markandrew/kiosk/music/receiver/PluginReceiver.kt').read_text()
        self.assertIn('Music Assistant Plugin ' + version, receiver)
        self.assertIn('MusicAssistantPlugin/' + version, (SOURCE / 'MusicApi.java').read_text())
        self.assertIn('versionName = "' + version + '"', (ROOT / 'player-build/bundle/build.gradle.kts').read_text())
        self.assertEqual(6, len(builder.sdk_sources()))
        import hashlib
        self.assertEqual('b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13', hashlib.sha256((ROOT / 'player-build/gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest())
        wrapper = (ROOT / 'player-build/gradle/wrapper/gradle-wrapper.properties').read_text()
        self.assertIn('distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06', wrapper)
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        for expected in ["'release', 'upload'", "'release', 'create'", 'dist/kiosk-satellite-plugin.json', 'python3 tools/test.py', 'python3 tools/release_check.py']:
            self.assertIn(expected, workflow)
        self.assertNotIn('--clobber', workflow)

    def test_no_temporary_logging_in_release_runtime(self):
        runtime = list(SOURCE.glob('*.java')) + list((ROOT / 'receiver').rglob('*.kt'))
        for file in runtime:
            with self.subTest(file=file.name):
                text = file.read_text()
                for forbidden in ['TemporaryVolumeLog', 'VolumeDebugEvent', 'volumeLog.', '10.44.', '6514', 'Start temporary logging']:
                    self.assertNotIn(forbidden, text)
        for name in ['volume_syslog.py', 'test_volume_syslog.py']:
            self.assertFalse((ROOT / 'tools' / name).exists())
        feature = (SOURCE / 'FeatureController.java').read_text()
        self.assertIn('"Lyrics are not available for radio playback."', feature)
        rules = (ROOT / 'player-build/bundle/rules.pro').read_text()
        self.assertIn('-assumenosideeffects class android.util.Log', rules)

    def test_release_workflow_publication_is_gated_and_single_shot(self):
        manifest = (ROOT / 'kiosk-satellite-plugin.json').read_text()
        version = json.loads(manifest)['version']
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        codes = [textwrap.dedent(x) for x in re.findall(r"          python3 - <<'PY'\n(.*?)\n          PY", workflow, re.S)]
        validate, publish = codes[0], codes[-1]
        with patch.dict(os.environ, {'GITHUB_REF_NAME': 'v' + version, 'GITHUB_EVENT_NAME': 'push'}), patch('builtins.open', mock_open(read_data=manifest)), patch('os.path.isfile', return_value=True):
            exec(validate, {})
            with patch('subprocess.check_call') as call:
                exec(publish, {})
                self.assertEqual(1, call.call_count)
                self.assertEqual(['gh', 'release', 'create', 'v' + version], call.call_args.args[0][:4])
                self.assertEqual(['dist/musicassistantplugin-' + version + '.zip', 'dist/musicassistantplugin-' + version + '.zip.sha256', 'dist/kiosk-satellite-plugin.json'], call.call_args.args[0][-3:])
            with patch('subprocess.check_call', side_effect=subprocess.CalledProcessError(1, ['gh'])) as call:
                with self.assertRaises(subprocess.CalledProcessError):
                    exec(publish, {})
                self.assertEqual(1, call.call_count)
            with patch.dict(os.environ, {'GITHUB_REF_NAME': 'v999.0.0'}), self.assertRaises(AssertionError):
                exec(validate, {})
            with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'release'}), patch('subprocess.check_output', return_value=b'{"isDraft": false, "isPrerelease": false}'), patch('subprocess.check_call') as call:
                exec(validate, {})
                exec(publish, {})
                self.assertEqual(['gh', 'release', 'upload'], call.call_args.args[0][:3])

    def test_protocol_parsing_remains_bounded(self):
        setup = (SOURCE / "SetupController.java").read_text()
        self.assertNotIn('panel.add("Discovery diagnostics', setup)
        api = (SOURCE / "MusicApi.java").read_text()
        self.assertIn('p.optJSONArray("output_protocols")', api)
        self.assertIn('Math.min(protocols.length(), 8)', api)
        self.assertIn('DiscoveryDiagnostics.token(protocol.optString("protocol_domain"', api)
        self.assertIn('Boolean.TRUE.equals(protocol.opt("available"))', api)
        self.assertIn('j < protocols.length()', api)
        self.assertIn("DiscoveryPolicy.sendspin(", (SOURCE / "FollowPolicy.java").read_text())

    def test_nullable_ma_references(self):
        api = (SOURCE / "MusicApi.java").read_text()
        for field in ["active_group", "synced_to", "active_source"]:
            self.assertIn('PlayerPolicy.reference(p.opt("' + field + '"), JSONObject.NULL)', api)
            self.assertNotIn('p.optString("' + field + '"', api)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", action="store_true", help="Also compile/run pure Java policy tests with an installed JDK")
    parser.add_argument("--json-jar", help="Optional org.json JAR for real MusicApi loopback HTTP tests (test-only dependency)")
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SourceChecks))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.java:
        if not shutil.which("javac") or not shutil.which("java"):
            raise SystemExit("Java policy tests NOT run: install JDK 17+ in the build environment.")
        cache = ROOT / "build"
        cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="policy-tests-", dir=cache) as folder:
            subprocess.run(["javac", "--release", "8", "-d", folder, str(SOURCE / "IngressPolicy.java"), str(SOURCE / "TouchLayout.java"), str(SOURCE / "PlayerPolicy.java"), str(SOURCE / "DiscoveryPolicy.java"), str(ROOT / "tests/IngressPolicyTest.java"), str(ROOT / "tests/PlayerPolicyTest.java"), str(ROOT / "tests/DiscoveryPolicyTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "IngressPolicyTest"], check=True)
            subprocess.run(["java", "-cp", folder, "PlayerPolicyTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-d", folder, str(SOURCE / "OwnReceiverPolicy.java"), str(ROOT / "tests/OwnReceiverPolicyTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.OwnReceiverPolicyTest"], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.DiscoveryPolicyTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "DefaultPlayerPolicy.java"), str(ROOT / "tests/DefaultPlayerPolicyTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.DefaultPlayerPolicyTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "DiscoveryDiagnostics.java"), str(ROOT / "tests/DiscoveryDiagnosticsTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.DiscoveryDiagnosticsTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(ROOT / "tests/bridge-stub/me/jxl/kiosk/plugins/PluginHost.java"),
                str(SOURCE / "KioskPlayerBridge.java"), str(ROOT / "tests/KioskPlayerBridgeTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.KioskPlayerBridgeTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "ReceiverNamePolicy.java"), str(SOURCE / "KioskNameAccess.java"),
                str(ROOT / "tests/ReceiverNamingTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.ReceiverNamingTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "FollowPolicy.java"), str(ROOT / "tests/FollowPolicyTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.FollowPolicyTest"], check=True)
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "NodeResolver.java"),
                str(ROOT / "tests/discovery-stub/net/markandrew/kiosk/music/ServiceCatalogue.java"),
                str(SOURCE / "StandaloneServer.java"), str(SOURCE / "DiscoveryAccess.java"), str(ROOT / "tests/DiscoveryAccessTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.DiscoveryAccessTest"], check=True)
            controller_folder = Path(folder) / "controller"
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", folder,
                str(SOURCE / "IdleRevert.java"), str(ROOT / "tests/IdleRevertTest.java")], check=True)
            subprocess.run(["java", "-cp", folder, "net.markandrew.kiosk.music.IdleRevertTest"], check=True)
            subprocess.run(["javac","--release","8","-cp",folder,"-d",folder,
                str(SOURCE/"AudioGainPolicy.java"),str(SOURCE/"PlaybackLaunchPolicy.java"),str(ROOT/"tests/PlaybackIntegrationPolicyTest.java")],check=True)
            subprocess.run(["java","-cp",folder,"net.markandrew.kiosk.music.PlaybackIntegrationPolicyTest"],check=True)
            subprocess.run(["javac","--release","8","-cp",folder,"-d",folder,str(SOURCE/"IngressAccess.java"),str(ROOT/"tests/IngressAccessTest.java")],check=True)
            subprocess.run(["java","-cp",folder,"net.markandrew.kiosk.music.IngressAccessTest"],check=True)
            controller_folder.mkdir()
            subprocess.run(["javac", "--release", "8", "-cp", folder, "-d", str(controller_folder),
                *[str(p) for p in (ROOT / "tests/controller-stub").rglob("*.java")],
                str(SOURCE / "PlayerController.java"), str(SOURCE / "SetupController.java"),
                str(ROOT / "tests/PlayerControllerTest.java"), str(ROOT / "tests/SetupControllerTest.java")], check=True)
            subprocess.run(["java", "-cp", str(controller_folder) + ":" + folder,
                "net.markandrew.kiosk.music.PlayerControllerTest"], check=True)
            subprocess.run(["java", "-cp", str(controller_folder) + ":" + folder,
                "net.markandrew.kiosk.music.SetupControllerTest"], check=True)
            if args.json_jar:
                subprocess.run(["javac", "--release", "8", "-cp", args.json_jar, "-d", folder,
                    str(SOURCE / "PairingDiagnostics.java"), str(ROOT / "tests/PairingDiagnosticsTest.java")], check=True)
                subprocess.run(["java", "-cp", folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.PairingDiagnosticsTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-cp", args.json_jar, "-d", folder,
                    str(SOURCE / "VisibilityDiagnostics.java"), str(ROOT / "tests/VisibilityDiagnosticsTest.java")], check=True)
                subprocess.run(["java", "-cp", folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.VisibilityDiagnosticsTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-cp", args.json_jar, "-d", folder,
                    str(SOURCE / "PairingDiagnostics.java"), str(SOURCE / "AppPairingFlow.java"),
                    str(ROOT / "tests/AppPairingFlowTest.java")], check=True)
                subprocess.run(["java", "-cp", folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.AppPairingFlowTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-cp", args.json_jar, "-d", folder,
                    str(SOURCE / "PairingDiagnostics.java"), str(SOURCE / "PairingSteps.java"), str(SOURCE / "ReceiverPairingFlow.java"),
                    str(ROOT / "tests/ReceiverPairingFlowTest.java")], check=True)
                subprocess.run(["java", "-cp", folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.ReceiverPairingFlowTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-cp", args.json_jar, "-d", folder,
                    str(SOURCE / "PairingSteps.java"), str(ROOT / "tests/PairingStepsTest.java")], check=True)
                subprocess.run(["java", "-cp", folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.PairingStepsTest"], check=True)
                now_folder = Path(folder) / "now-playing"
                now_folder.mkdir()
                subprocess.run(["javac", "--release", "8", "-cp", folder + ":" + args.json_jar, "-d", str(now_folder),
                    str(SOURCE / "VolumeDisplay.java"),str(SOURCE / "BrowserItem.java"), str(SOURCE / "BrowserPage.java"), str(SOURCE / "RadioMetadata.java"),str(SOURCE / "NowPlayingData.java"),
                    str(SOURCE / "NowPlayingAction.java"), str(SOURCE / "NowPlayingResult.java"), str(SOURCE / "MusicPanelLayout.java"),
                    str(ROOT / "tests/NowPlayingActionTest.java"), str(ROOT / "tests/NowPlayingDataTest.java")], check=True)
                subprocess.run(["java", "-cp", str(now_folder) + ":" + folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.NowPlayingDataTest"], check=True)
                subprocess.run(["java", "-cp", str(now_folder) + ":" + folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.NowPlayingActionTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-d", str(now_folder),
                    str(SOURCE / "NowPlayingWork.java"), str(ROOT / "tests/NowPlayingWorkTest.java")], check=True)
                subprocess.run(["java", "-cp", str(now_folder), "net.markandrew.kiosk.music.NowPlayingWorkTest"], check=True)
                api_folder = Path(folder) / "api"
                api_folder.mkdir()
                subprocess.run(["javac", "--release", "8", "-cp", folder + ":" + args.json_jar, "-d", str(api_folder),
                    str(ROOT / "tests/api-stub/net/markandrew/kiosk/music/IngressAccess.java"),
                    str(SOURCE / "VolumeDisplay.java"),str(SOURCE / "MusicApi.java"), str(ROOT / "tests/MusicApiTest.java"), str(ROOT / "tests/MusicApiDiagnosticsTest.java")], check=True)
                subprocess.run(["java", "-cp", str(api_folder) + ":" + folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.MusicApiTest"], check=True)
                subprocess.run(["java", "-cp", str(api_folder) + ":" + folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.MusicApiDiagnosticsTest"], check=True)
                subprocess.run(["javac", "--release", "8", "-cp", str(api_folder) + ":" + folder + ":" + args.json_jar, "-d", str(api_folder),
                    str(SOURCE / "BrowserPage.java"), str(SOURCE / "BrowserItem.java"), str(SOURCE / "LyricsData.java"),
                    str(SOURCE / "FeatureApi.java"), str(ROOT / "tests/FeatureApiTest.java")], check=True)
                subprocess.run(["java", "-cp", str(api_folder) + ":" + folder + ":" + args.json_jar,
                    "net.markandrew.kiosk.music.FeatureApiTest"], check=True)
    else:
        print("Source/manifest checks only. Java compilation, Android UI and live Ingress have NOT been tested.")
