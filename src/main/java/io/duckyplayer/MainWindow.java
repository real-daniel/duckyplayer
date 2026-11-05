package io.duckyplayer;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

@SuppressWarnings("serial")
final class MainWindow extends JFrame implements PlaybackController.Listener {
    private static final long serialVersionUID = 1L;

    private final JTextField replayPath = new JTextField();
    private final JComboBox<NativeWindows.TargetWindow> target = new JComboBox<>();
    private final JSpinner offset = new JSpinner(new SpinnerNumberModel(0, -60_000, 60_000, 1));
    private final JComboBox<PlaybackController.OutputRate> outputRate =
            new JComboBox<>(PlaybackController.OutputRate.values());
    private final JComboBox<NativeWindows.PlaybackKey> keyOne =
            new JComboBox<>(NativeWindows.PlaybackKey.values());
    private final JComboBox<NativeWindows.PlaybackKey> keyTwo =
            new JComboBox<>(NativeWindows.PlaybackKey.values());
    private final Preferences preferences = Preferences.userNodeForPackage(MainWindow.class);
    private final JButton browse = new JButton("Browse…");
    private final JButton editReplay = new JButton("Edit replay…");
    private final JButton refresh = new JButton("Refresh");
    private final JButton audioArm = new JButton("Arm audio sync");
    private final JButton memoryArm = new JButton("Arm memory sync");
    private final JButton measureClock = new JButton("Measure clock (no input)");
    private final JButton autoArm = new JButton("Arm log sync (fallback)");
    private final JButton manualArm = new JButton("Arm manual (F8)");
    private final JButton stop = new JButton("Stop (F12)");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel("Load a replay to begin. F12 always releases injected inputs.");
    private final PlaybackController playback = new PlaybackController(this);
    private final NativeWindows.GlobalHotkeys hotkeys = new NativeWindows.GlobalHotkeys(this::hotkeyPressed);
    private final LazerLogSynchronizer logSynchronizer = new LazerLogSynchronizer();
    private final AudioStartSynchronizer audioSynchronizer = new AudioStartSynchronizer();
    private ReplayData replay;
    private ReplayDocument replayDocument;
    private ArmMode armMode = ArmMode.NONE;

    private enum ArmMode { NONE, MANUAL, AUTOMATIC, AUDIO }

    MainWindow() {
        super("Ducky Player");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 425));
        setSize(960, 450);
        setLocationByPlatform(true);
        buildUi();
        keyOne.setSelectedItem(savedKey("keyOne", NativeWindows.PlaybackKey.Z));
        keyTwo.setSelectedItem(savedKey("keyTwo", NativeWindows.PlaybackKey.X));
        installActions();
        refreshTargets();
        outputRate.setSelectedItem(PlaybackController.OutputRate.HZ_1000);
        hotkeys.start();
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;

        replayPath.setEditable(false);
        replayPath.setText("Drop an .osr file here or click Browse…");
        replayPath.setToolTipText("Drop an osu! replay file here");
        JPanel replayButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        replayButtons.add(browse);
        replayButtons.add(editReplay);
        addRow(form, constraints, "Replay", replayPath, replayButtons);
        addRow(form, constraints, "Target", target, refresh);
        addRow(form, constraints, "Offset (ms)", offset,
                new JLabel("Positive values start input later"));
        addRow(form, constraints, "Cursor output", outputRate,
                new JLabel("Keys retain exact replay timestamps"));
        JPanel keySelectors = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        keySelectors.add(new JLabel("K1"));
        keySelectors.add(keyOne);
        keySelectors.add(new JLabel("K2"));
        keySelectors.add(keyTwo);
        addRow(form, constraints, "Replay keys", keySelectors,
                new JLabel("Mouse lanes remain left/right click"));

        JPanel buttons = new JPanel(new java.awt.GridLayout(2, 3, 8, 4));
        buttons.add(audioArm);
        buttons.add(memoryArm);
        buttons.add(measureClock);
        buttons.add(autoArm);
        buttons.add(manualArm);
        buttons.add(stop);
        memoryArm.addActionListener(event -> startMemory(false));
        measureClock.addActionListener(event -> startMemory(true));
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        form.add(buttons, constraints);

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 19, 14, 19));
        GridBagConstraints lower = new GridBagConstraints();
        lower.gridx = 0;
        lower.gridy = 0;
        lower.weightx = 1;
        lower.fill = GridBagConstraints.HORIZONTAL;
        lower.insets = new Insets(4, 0, 4, 0);
        bottom.add(progress, lower);
        lower.gridy++;
        bottom.add(status, lower);
        lower.gridy++;
        bottom.add(new JLabel("Local replay recording only. Use standard lazer playfield scaling and disable High precision mouse."), lower);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        stop.setEnabled(false);
        editReplay.setEnabled(false);
        autoArm.setEnabled(false);
        manualArm.setEnabled(false);
    }

    private static void addRow(JPanel panel, GridBagConstraints constraints, String label,
                               java.awt.Component main, java.awt.Component trailing) {
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(main, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(trailing, constraints);
        constraints.gridy++;
    }

    private void installActions() {
        browse.addActionListener(event -> browseReplay());
        editReplay.addActionListener(event -> openEditor());
        refresh.addActionListener(event -> refreshTargets());
        audioArm.addActionListener(event -> armWithAudio());
        autoArm.addActionListener(event -> armAutomatically());
        manualArm.addActionListener(event -> armManually());
        stop.addActionListener(event -> emergencyStop("Stopped. All injected inputs released."));
        keyOne.addActionListener(event -> saveKeys());
        keyTwo.addActionListener(event -> saveKeys());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                hotkeys.close();
                audioSynchronizer.close();
                logSynchronizer.close();
                playback.close();
                dispose();
            }
        });
        TransferHandler replayDropHandler = new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Transferable transferable = support.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) return false;
                    File file = files.getFirst();
                    if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".osr")) {
                        setStatus("Drop rejected: select an .osr replay file.");
                        return false;
                    }
                    loadReplay(file.toPath());
                    return true;
                } catch (Exception exception) {
                    setStatus("Drop failed: " + exception.getMessage());
                    return false;
                }
            }
        };
        setTransferHandler(replayDropHandler);
        replayPath.setTransferHandler(replayDropHandler);
    }

    private void armWithAudio() {
        if (!canArm()) return;
        if (replay.clockRate() != 1.0) {
            setStatus("Audio sync currently supports replays without DT, NC, or HT.");
            return;
        }
        armMode = ArmMode.AUDIO;
        setControlsLocked(true);
        audioSynchronizer.arm(replay, new AudioStartSynchronizer.Listener() {
            @Override
            public void onStatus(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (armMode == ArmMode.AUDIO) setStatus(message + " F12 cancels.");
                });
            }

            @Override
            public void onSynchronized(AudioStartSynchronizer.SyncPoint point) {
                SwingUtilities.invokeLater(() -> beginAudioPlayback(point));
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (armMode != ArmMode.AUDIO) return;
                    armMode = ArmMode.NONE;
                    setControlsLocked(false);
                    setStatus(message);
                });
            }
        });
    }

    private void browseReplay() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open osu! replay");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("osu! replay (*.osr)", "osr"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            loadReplay(chooser.getSelectedFile().toPath());
    }

    private void loadReplay(Path path) {
        try {
            ReplayDocument loadedDocument = ReplayReader.loadDocument(path);
            ReplayData loaded = loadedDocument.data();
            replay = loaded;
            replayDocument = loadedDocument;
            armMode = ArmMode.NONE;
            replayPath.setText(path.toAbsolutePath().toString());
            double seconds = loaded.frames().getLast().timeMs() / loaded.clockRate() / 1000.0;
            setStatus(String.format("Loaded %,d frames (%.1f s), player: %s, mods: %s",
                    loaded.frames().size(), seconds, loaded.playerName(), loaded.modSummary()));
            updateArmButtons();
            progress.setValue(0);
        } catch (IOException | RuntimeException exception) {
            replay = null;
            replayDocument = null;
            armMode = ArmMode.NONE;
            updateArmButtons();
            setStatus("Load failed: " + exception.getMessage());
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Could not load replay", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEditor() {
        if (replayDocument == null) return;
        ReplayEditorWindow editor = new ReplayEditorWindow(replayDocument, this::loadReplay);
        editor.setVisible(true);
    }

    private void refreshTargets() {
        List<NativeWindows.TargetWindow> targets = NativeWindows.findOsuWindows();
        target.setModel(new DefaultComboBoxModel<>(targets.toArray(NativeWindows.TargetWindow[]::new)));
        updateArmButtons();
        if (targets.isEmpty()) setStatus("No osu! window found. Launch osu!lazer and click Refresh.");
    }

    private boolean canArm() {
        if (replay == null) {
            setStatus("Load a replay first.");
            return false;
        }
        if (target.getSelectedItem() == null) {
            setStatus("Select a running osu! window first.");
            return false;
        }
        NativeWindows.PlaybackKey first = (NativeWindows.PlaybackKey) keyOne.getSelectedItem();
        NativeWindows.PlaybackKey second = (NativeWindows.PlaybackKey) keyTwo.getSelectedItem();
        if (first == second) {
            setStatus("Choose different keys for K1 and K2.");
            return false;
        }
        playback.setKeyboardKeys(first, second);
        return true;
    }

    private NativeWindows.PlaybackKey savedKey(String name, NativeWindows.PlaybackKey fallback) {
        try {
            return NativeWindows.PlaybackKey.valueOf(preferences.get(name, fallback.name()));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private void saveKeys() {
        NativeWindows.PlaybackKey first = (NativeWindows.PlaybackKey) keyOne.getSelectedItem();
        NativeWindows.PlaybackKey second = (NativeWindows.PlaybackKey) keyTwo.getSelectedItem();
        if (first == null || second == null || first == second) return;
        preferences.put("keyOne", first.name());
        preferences.put("keyTwo", second.name());
    }

    private void armAutomatically() {
        if (!canArm()) return;
        audioSynchronizer.cancel();
        armMode = ArmMode.AUTOMATIC;
        setControlsLocked(true);
        logSynchronizer.arm(new LazerLogSynchronizer.Listener() {
            @Override
            public void onStatus(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (armMode == ArmMode.AUTOMATIC) setStatus(message + " F12 cancels.");
                });
            }

            @Override
            public void onSynchronized(LazerLogSynchronizer.SyncPoint point) {
                SwingUtilities.invokeLater(() -> beginAutomaticPlayback(point));
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    if (armMode != ArmMode.AUTOMATIC) return;
                    armMode = ArmMode.NONE;
                    setControlsLocked(false);
                    setStatus(error);
                });
            }
        });
    }

    private void armManually() {
        if (!canArm()) return;
        audioSynchronizer.cancel();
        logSynchronizer.cancel();
        armMode = ArmMode.MANUAL;
        setControlsLocked(true);
        setStatus("Manual mode armed. Focus osu! at beatmap time zero and press F8. F12 cancels.");
    }

    private void beginAudioPlayback(AudioStartSynchronizer.SyncPoint point) {
        if (armMode != ArmMode.AUDIO || replay == null) return;
        NativeWindows.TargetWindow selected = (NativeWindows.TargetWindow) target.getSelectedItem();
        if (selected == null || !NativeWindows.isForeground(selected.handle())) {
            armMode = ArmMode.NONE;
            setControlsLocked(false);
            setStatus("Audio start rejected because the selected osu! window was not foreground.");
            return;
        }
        PlaybackController.OutputRate rate = (PlaybackController.OutputRate) outputRate.getSelectedItem();
        armMode = ArmMode.NONE;
        if (!playback.startAt(selected, replay, (Integer) offset.getValue(), rate, point.audioZeroNanos())) {
            setControlsLocked(false);
            setStatus("Could not start audio-synchronized playback.");
            return;
        }
        setStatus(String.format("Audio locked (envelope %.3f, waveform %.3f). F12 stops.",
                point.envelopeCorrelation(), point.waveformCorrelation()));
    }

    private void hotkeyPressed(int hotkey) {
        SwingUtilities.invokeLater(() -> {
            if (hotkey == NativeWindows.HOTKEY_START_FAILED)
                setStatus("Warning: F8 could not be registered. Another application may be using it.");
            else if (hotkey == NativeWindows.HOTKEY_STOP_FAILED)
                setStatus("Warning: F12 emergency stop could not be registered.");
            else if (hotkey == NativeWindows.HOTKEY_STOP) emergencyStop("Emergency stop. All injected inputs released.");
            else if (hotkey == NativeWindows.HOTKEY_START) beginManualPlayback();
        });
    }

    private void beginManualPlayback() {
        if (armMode != ArmMode.MANUAL || replay == null) return;
        NativeWindows.TargetWindow selected = (NativeWindows.TargetWindow) target.getSelectedItem();
        if (selected == null || !NativeWindows.isForeground(selected.handle())) {
            setStatus("Start rejected: the selected osu! window must be in the foreground.");
            return;
        }
        armMode = ArmMode.NONE;
        PlaybackController.OutputRate rate = (PlaybackController.OutputRate) outputRate.getSelectedItem();
        if (!playback.start(selected, replay, (Integer) offset.getValue(), rate)) {
            setStatus("Could not start playback.");
            setControlsLocked(false);
            return;
        }
    }

    private void beginAutomaticPlayback(LazerLogSynchronizer.SyncPoint point) {
        if (armMode != ArmMode.AUTOMATIC || replay == null) return;
        NativeWindows.TargetWindow selected = (NativeWindows.TargetWindow) target.getSelectedItem();
        if (selected == null || !NativeWindows.isForeground(selected.handle())) {
            armMode = ArmMode.NONE;
            setControlsLocked(false);
            setStatus("Automatic start rejected because the selected osu! window was not foreground.");
            return;
        }
        PlaybackController.OutputRate rate = (PlaybackController.OutputRate) outputRate.getSelectedItem();
        long replayZero = point.replayZeroNanos(replay.clockRate());
        double untilZeroMs = (replayZero - System.nanoTime()) / 1_000_000.0;
        armMode = ArmMode.NONE;
        if (!playback.startAt(selected, replay, (Integer) offset.getValue(), rate, replayZero)) {
            setControlsLocked(false);
            setStatus("Could not start automatically synchronized playback.");
            return;
        }
        setStatus(String.format("Synchronized at %.3f ms; replay zero in %.1f ms. F12 stops.",
                point.initialClockMs(), untilZeroMs));
    }

    private void emergencyStop(String message) {
        armMode = ArmMode.NONE;
        audioSynchronizer.cancel();
        logSynchronizer.cancel();
        playback.stop();
        setControlsLocked(false);
        setStatus(message);
    }

    private void setControlsLocked(boolean locked) {
        browse.setEnabled(!locked);
        editReplay.setEnabled(!locked && replayDocument != null);
        refresh.setEnabled(!locked);
        target.setEnabled(!locked);
        offset.setEnabled(!locked);
        outputRate.setEnabled(!locked);
        keyOne.setEnabled(!locked);
        keyTwo.setEnabled(!locked);
        stop.setEnabled(locked);
        updateArmButtons();
    }

    private void updateArmButtons() {
        boolean enabled = replay != null && target.getSelectedItem() != null
                && !playback.isRunning() && armMode == ArmMode.NONE;
        autoArm.setEnabled(enabled);
        audioArm.setEnabled(enabled);
        manualArm.setEnabled(enabled);
        memoryArm.setEnabled(enabled);
        measureClock.setEnabled(target.getSelectedItem() != null && !playback.isRunning() && armMode == ArmMode.NONE);
        editReplay.setEnabled(replayDocument != null && !playback.isRunning() && armMode == ArmMode.NONE);
    }

    private void startMemory(boolean measureOnly) {
        NativeWindows.TargetWindow selected = (NativeWindows.TargetWindow) target.getSelectedItem();
        if (!measureOnly && !canArm()) return;
        audioSynchronizer.cancel(); logSynchronizer.cancel();
        if (playback.startLive(selected, replay, (Integer) offset.getValue(),
                (PlaybackController.OutputRate) outputRate.getSelectedItem(), measureOnly)) {
            setControlsLocked(true);
            setStatus("Discovering game root (up to 30 seconds). Wait for Clock ready. F12 cancels.");
        }
    }

    @Override public void onStatus(String message) { setStatus(message); }

    private void setStatus(String message) {
        status.setText(message);
        status.setToolTipText(message);
    }

    @Override
    public void onStarted() {
        setStatus("Playback scheduler active. Keep osu! focused; F12 stops immediately.");
    }

    @Override
    public void onProgress(int perMille) {
        progress.setValue(perMille);
    }

    @Override
    public void onStopped(PlaybackController.StopReason reason, String detail) {
        armMode = ArmMode.NONE;
        setControlsLocked(false);
        if (reason == PlaybackController.StopReason.COMPLETED) progress.setValue(1000);
        setStatus(detail);
    }

    static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
            // Swing's cross-platform look and feel remains usable.
        }
    }
}
