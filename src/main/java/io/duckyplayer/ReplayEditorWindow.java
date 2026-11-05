package io.duckyplayer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@SuppressWarnings("serial")
final class ReplayEditorWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final int HISTORY_LIMIT = 50;

    private final ReplayDocument document;
    private final Consumer<Path> savedReplay;
    private final List<ReplayFrame> frames;
    private final Deque<List<ReplayFrame>> undo = new ArrayDeque<>();
    private final Deque<List<ReplayFrame>> redo = new ArrayDeque<>();
    private final FrameTableModel tableModel = new FrameTableModel();
    private final JTable table = new JTable(tableModel);
    private final EditorCanvas canvas = new EditorCanvas();
    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");
    private final JButton playButton = new JButton("Play");
    private final JButton reverseInputsButton = new JButton("Reverse inputs");
    private final JSpinner brushSize = new JSpinner(new SpinnerNumberModel(48, 8, 256, 4));
    private final JComboBox<BrushMode> brushMode = new JComboBox<>(BrushMode.values());
    private final JSpinner brushStrength = new JSpinner(new SpinnerNumberModel(35, 5, 100, 5));
    private final JSpinner windowSize = new JSpinner(new SpinnerNumberModel(1300, 400, 10_000, 100));
    private final JSpinner inputBlockSize = new JSpinner(new SpinnerNumberModel(50, 1, 500, 5));
    private final JSpinner timingSafetyMargin = new JSpinner(new SpinnerNumberModel(2, 0, 20, 1));
    private final JLabel status = new JLabel("Drag the path to reshape it; click a key lane to toggle that segment.");
    private final JLabel metrics = new JLabel("Hit evaluation waiting for beatmap…");
    private final Timer previewTimer;
    private BeatmapData beatmap;
    private ReplayAnalysis analysis;
    private JDialog frameDialog;
    private long playheadMs;
    private long previewStartedNanos;
    private long previewStartedAtMs;

    private enum BrushMode {
        MOVE("Move"), SQUISH("Squish");

        private final String label;

        BrushMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    ReplayEditorWindow(ReplayDocument document, Consumer<Path> savedReplay) {
        super("Ducky Player — Replay Editor — " + document.data().playerName());
        this.document = document;
        this.savedReplay = savedReplay;
        frames = new ArrayList<>(document.data().frames());
        playheadMs = frames.getFirst().timeMs();
        previewTimer = new Timer(16, event -> advancePreview());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 750));
        setSize(1320, 900);
        setLocationByPlatform(true);
        buildUi();
        installActions();
        installBeatmapDrop();
        updateHistoryButtons();
        loadBeatmapAutomatically();
    }

    private void buildUi() {
        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        JButton save = new JButton("Save As…");
        save.setActionCommand("save");
        JButton mirror = new JButton("Mirror vertically");
        mirror.setActionCommand("mirror");
        JButton add = new JButton("Add frame");
        add.setActionCommand("add");
        JButton delete = new JButton("Delete frame");
        delete.setActionCommand("delete");
        JButton loadBeatmap = new JButton("Load .osu…");
        loadBeatmap.setActionCommand("beatmap");
        JButton previousMiss = new JButton("◀ Miss");
        previousMiss.setActionCommand("previous-miss");
        JButton nextMiss = new JButton("Miss ▶");
        nextMiss.setActionCommand("next-miss");
        JButton framesButton = new JButton("Frames…");
        framesButton.setActionCommand("frames");
        tools.add(save);
        tools.addSeparator();
        tools.add(undoButton);
        tools.add(redoButton);
        tools.addSeparator();
        tools.add(playButton);
        tools.add(new JLabel("  Mods: " + document.data().modSummary() + "  "));
        tools.add(mirror);
        reverseInputsButton.setEnabled(false);
        reverseInputsButton.setToolTipText(
                "Swap M1 with M2 and K1 with K2 inside the selected input time range");
        tools.add(reverseInputsButton);
        tools.add(loadBeatmap);
        tools.add(previousMiss);
        tools.add(nextMiss);
        tools.addSeparator();
        tools.add(new JLabel(" Brush "));
        tools.add(brushMode);
        tools.add(brushSize);
        tools.add(new JLabel(" px  Strength "));
        tools.add(brushStrength);
        tools.add(new JLabel("%   Window "));
        tools.add(windowSize);
        tools.add(new JLabel(" ms "));
        tools.add(new JLabel(" Input block "));
        tools.add(inputBlockSize);
        tools.add(new JLabel(" ms "));
        tools.addSeparator();
        tools.add(framesButton);

        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(130);
        add.addActionListener(event -> addFrame());
        delete.addActionListener(event -> deleteFrames());

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(5, 10, 7, 10));
        footer.add(status, BorderLayout.CENTER);
        JPanel estimateControls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        estimateControls.add(new JLabel("Accuracy safety"));
        timingSafetyMargin.setToolTipText(
                "Shrinks hit windows to reserve time for playback and recording jitter");
        estimateControls.add(timingSafetyMargin);
        estimateControls.add(new JLabel("ms"));
        estimateControls.add(metrics);
        footer.add(estimateControls, BorderLayout.EAST);

        add(tools, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        for (java.awt.Component component : tools.getComponents()) {
            if (component instanceof JButton button) {
                if ("save".equals(button.getActionCommand())) button.addActionListener(event -> saveAs());
                if ("mirror".equals(button.getActionCommand())) button.addActionListener(event -> mirrorVertically());
                if ("add".equals(button.getActionCommand())) button.addActionListener(event -> addFrame());
                if ("delete".equals(button.getActionCommand())) button.addActionListener(event -> deleteFrames());
                if ("beatmap".equals(button.getActionCommand())) button.addActionListener(event -> chooseBeatmap());
                if ("previous-miss".equals(button.getActionCommand())) button.addActionListener(event -> jumpToMiss(false));
                if ("next-miss".equals(button.getActionCommand())) button.addActionListener(event -> jumpToMiss(true));
                if ("frames".equals(button.getActionCommand())) button.addActionListener(event -> showFrameTable(add, delete));
            }
        }
    }

    private void showFrameTable(JButton add, JButton delete) {
        if (frameDialog == null) {
            frameDialog = new JDialog(this, "Precise replay frames", false);
            JScrollPane tableScroll = new JScrollPane(table);
            tableScroll.setBorder(BorderFactory.createTitledBorder(
                    "Time, cursor position, and unsigned key mask"));
            JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
            buttons.add(add);
            buttons.add(delete);
            frameDialog.add(tableScroll, BorderLayout.CENTER);
            frameDialog.add(buttons, BorderLayout.SOUTH);
            frameDialog.setSize(720, 520);
            frameDialog.setLocationRelativeTo(this);
        }
        frameDialog.setVisible(true);
        frameDialog.toFront();
    }

    private void loadBeatmapAutomatically() {
        status.setText("Finding the matching beatmap for the hit-object overlay…");
        new SwingWorker<BeatmapData, Void>() {
            @Override
            protected BeatmapData doInBackground() throws Exception {
                Path path = new BeatmapAudioResolver().findBeatmap(document.data().beatmapHash()).orElse(null);
                return path == null ? null : BeatmapData.load(path).withMods(document.data());
            }

            @Override
            protected void done() {
                try {
                    beatmap = get();
                    if (beatmap == null) {
                        status.setText("Matching .osu not found in the stable Songs library; use Load .osu…");
                    } else {
                        refreshAnalysis();
                        status.setText(String.format(Locale.ROOT,
                                "Loaded %,d hit objects. Drag the path or click an input lane to edit.",
                                beatmap.hitObjects().size()));
                    }
                    canvas.repaint();
                } catch (Exception exception) {
                    status.setText("Could not load the beatmap overlay: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void chooseBeatmap() {
        JFileChooser chooser = new JFileChooser(document.source().getParent().toFile());
        chooser.setDialogTitle("Select the matching osu! beatmap or beatmapset");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "osu! beatmap or beatmapset (*.osu, *.osz)", "osu", "osz"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        loadBeatmap(chooser.getSelectedFile().toPath(), true);
    }

    private boolean loadBeatmap(Path path, boolean showDialogOnError) {
        try {
            String filename = path.getFileName().toString();
            String displayName = filename;
            if (filename.toLowerCase(Locale.ROOT).endsWith(".osz")) {
                BeatmapArchive.Match match = BeatmapArchive.loadMatching(
                        path, document.data().beatmapHash());
                beatmap = match.beatmap().withMods(document.data());
                displayName = filename + " → " + match.entryName();
            } else {
                beatmap = BeatmapData.load(path).withMods(document.data());
            }
            refreshAnalysis();
            status.setText(String.format(Locale.ROOT, "Loaded %,d hit objects from %s.",
                    beatmap.hitObjects().size(), displayName));
            canvas.repaint();
            return true;
        } catch (IOException | RuntimeException exception) {
            status.setText("Could not load beatmap: " + exception.getMessage());
            if (showDialogOnError) {
                JOptionPane.showMessageDialog(this, exception.getMessage(),
                        "Could not load beatmap", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }

    private void installBeatmapDrop() {
        TransferHandler handler = new TransferHandler() {
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
                    String name = files.isEmpty() ? "" : files.getFirst().getName().toLowerCase(Locale.ROOT);
                    if (files.size() != 1 || !(name.endsWith(".osu") || name.endsWith(".osz"))) {
                        status.setText("Drop rejected: attach exactly one .osu or .osz file.");
                        return false;
                    }
                    return loadBeatmap(files.getFirst().toPath(), false);
                } catch (Exception exception) {
                    status.setText("Beatmap drop failed: " + rootMessage(exception));
                    return false;
                }
            }
        };
        setTransferHandler(handler);
        canvas.setTransferHandler(handler);
    }

    private static String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private void installActions() {
        undoButton.addActionListener(event -> undo());
        redoButton.addActionListener(event -> redo());
        playButton.addActionListener(event -> togglePreview());
        reverseInputsButton.addActionListener(event -> swapSelectedInputs());
        windowSize.addChangeListener(event -> canvas.repaint());
        brushSize.addChangeListener(event -> canvas.repaint());
        brushMode.addActionListener(event -> canvas.repaint());
        timingSafetyMargin.addChangeListener(event -> refreshAnalysis());
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row >= 0 && row < frames.size()) {
                playheadMs = frames.get(row).timeMs();
                canvas.repaint();
            }
        });
        getRootPane().registerKeyboardAction(event -> saveAs(),
                javax.swing.KeyStroke.getKeyStroke("control S"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event -> undo(),
                javax.swing.KeyStroke.getKeyStroke("control Z"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event -> redo(),
                javax.swing.KeyStroke.getKeyStroke("control Y"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event -> togglePreview(),
                javax.swing.KeyStroke.getKeyStroke("SPACE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        registerEditorKey("LEFT", -4, false);
        registerEditorKey("A", -4, false);
        registerEditorKey("RIGHT", 4, false);
        registerEditorKey("D", 4, false);
        registerEditorKey("Q", -50, true);
        registerEditorKey("E", 50, true);
    }

    private void registerEditorKey(String key, int amount, boolean changesWindow) {
        getRootPane().registerKeyboardAction(event -> {
            if (changesWindow) {
                int current = (Integer) windowSize.getValue();
                windowSize.setValue(Math.max(400, Math.min(10_000, current + amount)));
            } else {
                playheadMs = Math.max(frames.getFirst().timeMs(),
                        Math.min(frames.getLast().timeMs(), playheadMs + amount));
                canvas.repaint();
            }
        }, javax.swing.KeyStroke.getKeyStroke(key), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void remember() {
        undo.addLast(List.copyOf(frames));
        while (undo.size() > HISTORY_LIMIT) undo.removeFirst();
        redo.clear();
        updateHistoryButtons();
    }

    private void restore(List<ReplayFrame> snapshot) {
        frames.clear();
        frames.addAll(snapshot);
        refreshAnalysis();
        tableModel.fireTableDataChanged();
        canvas.repaint();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.addLast(List.copyOf(frames));
        restore(undo.removeLast());
        updateHistoryButtons();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.addLast(List.copyOf(frames));
        restore(redo.removeLast());
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        undoButton.setEnabled(!undo.isEmpty());
        redoButton.setEnabled(!redo.isEmpty());
    }

    private void mirrorVertically() {
        remember();
        for (int index = 0; index < frames.size(); index++) {
            ReplayFrame frame = frames.get(index);
            frames.set(index, new ReplayFrame(frame.timeMs(), frame.x(), 384.0 - frame.y(), frame.keys()));
        }
        changed("Mirrored the cursor path vertically.");
    }

    private void swapSelectedInputs() {
        if (!canvas.hasInputSelection()) return;
        remember();
        List<ReplayFrame> swapped = ReplayEdits.swapInputLanes(
                frames, canvas.inputSelectionStart(), canvas.inputSelectionEnd());
        frames.clear();
        frames.addAll(swapped);
        changed("Reversed M1/M2 and K1/K2 input from "
                + canvas.formatTime(canvas.inputSelectionStart()) + " to "
                + canvas.formatTime(canvas.inputSelectionEnd()) + ".");
    }

    private void addFrame() {
        int selected = table.getSelectedRow();
        int after = selected >= 0 ? selected : Math.max(0, nearestFrame(playheadMs));
        ReplayFrame base = frames.get(after);
        long nextTime = after + 1 < frames.size() ? frames.get(after + 1).timeMs() : base.timeMs() + 1;
        long time = Math.max(base.timeMs(), base.timeMs() + (nextTime - base.timeMs()) / 2);
        remember();
        frames.add(after + 1, new ReplayFrame(time, base.x(), base.y(), base.keys()));
        changed("Added a frame at " + time + " ms.");
        table.getSelectionModel().setSelectionInterval(after + 1, after + 1);
    }

    private void deleteFrames() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0 || selected.length >= frames.size()) return;
        remember();
        for (int index = selected.length - 1; index >= 0; index--) frames.remove(selected[index]);
        changed("Deleted " + selected.length + " frame(s).");
    }

    private void changed(String message) {
        tableModel.fireTableDataChanged();
        refreshAnalysis();
        canvas.repaint();
        status.setText(message);
    }

    private void refreshAnalysis() {
        if (beatmap == null) {
            analysis = null;
            metrics.setText("Hit evaluation waiting for beatmap…");
            return;
        }
        int safetyMarginMs = ((Number) timingSafetyMargin.getValue()).intValue();
        analysis = ReplayAnalysis.evaluate(beatmap, frames, document.data().mods(), safetyMarginMs);
        metrics.setText(String.format(Locale.ROOT,
                "Projected ACC %.2f%%  •  300: %,d  100: %,d  50: %,d  Miss: %,d  •  Max %dx",
                analysis.accuracy() * 100, analysis.count300(), analysis.count100(),
                analysis.count50(), analysis.misses(), analysis.maxCombo()));
    }

    private void jumpToMiss(boolean next) {
        if (analysis == null) return;
        Long candidateTime = null;
        for (ReplayAnalysis.Result result : analysis.results()) {
            if (result.judgement() != ReplayAnalysis.Judgement.MISS) continue;
            long time = result.object().startTime();
            if (next && time > playheadMs) {
                if (candidateTime == null || time < candidateTime) candidateTime = time;
            }
            if (!next && time < playheadMs && (candidateTime == null || time > candidateTime))
                candidateTime = time;
        }
        if (candidateTime == null) {
            status.setText(next ? "No later misses." : "No earlier misses.");
            return;
        }
        playheadMs = candidateTime;
        status.setText("Jumped to miss at " + playheadMs + " ms.");
        canvas.repaint();
    }

    private void togglePreview() {
        if (previewTimer.isRunning()) {
            previewTimer.stop();
            playButton.setText("Play");
            status.setText("Preview paused at " + playheadMs + " ms.");
        } else {
            if (playheadMs >= frames.getLast().timeMs()) playheadMs = frames.getFirst().timeMs();
            previewStartedAtMs = playheadMs;
            previewStartedNanos = System.nanoTime();
            previewTimer.start();
            playButton.setText("Pause");
        }
    }

    private void advancePreview() {
        long elapsedMs = (System.nanoTime() - previewStartedNanos) / 1_000_000L;
        playheadMs = previewStartedAtMs + Math.round(elapsedMs * document.data().clockRate());
        if (playheadMs >= frames.getLast().timeMs()) {
            playheadMs = frames.getLast().timeMs();
            previewTimer.stop();
            playButton.setText("Play");
        }
        canvas.repaint();
    }

    private void saveAs() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
        if (analysis == null) {
            JOptionPane.showMessageDialog(this,
                    "Load the matching beatmap before exporting so edited hit counts can be written to the replay.",
                    "Beatmap required for export", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser(document.source().getParent().toFile());
        chooser.setDialogTitle("Export edited replay");
        chooser.setFileFilter(new FileNameExtensionFilter("osu! replay (*.osr)", "osr"));
        String name = document.source().getFileName().toString();
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".osr");
        chooser.setSelectedFile(new java.io.File((dot >= 0 ? name.substring(0, dot) : name) + "-edited.osr"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".osr"))
            destination = destination.resolveSibling(destination.getFileName() + ".osr");
        try {
            ReplayWriter.save(document, frames, analysis, destination);
            ReplayReader.load(destination);
            status.setText("Saved and verified with refreshed estimated hit counts: "
                    + destination.toAbsolutePath());
            savedReplay.accept(destination);
        } catch (IOException | RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Could not export replay", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int nearestFrame(long time) {
        int low = 0;
        int high = frames.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).timeMs() < time) low = middle + 1;
            else high = middle;
        }
        if (low > 0 && Math.abs(frames.get(low - 1).timeMs() - time) <= Math.abs(frames.get(low).timeMs() - time))
            return low - 1;
        return low;
    }

    private final class FrameTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"#", "Time (ms)", "X", "Y", "Keys"};

        @Override public int getRowCount() { return frames.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public boolean isCellEditable(int row, int column) { return column > 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Integer.class : String.class; }

        @Override
        public Object getValueAt(int row, int column) {
            ReplayFrame frame = frames.get(row);
            return switch (column) {
                case 0 -> row + 1;
                case 1 -> Long.toString(frame.timeMs());
                case 2 -> Double.toString(frame.x());
                case 3 -> Double.toString(frame.y());
                case 4 -> Integer.toUnsignedString(frame.keys());
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            ReplayFrame old = frames.get(row);
            try {
                ReplayFrame replacement = switch (column) {
                    case 1 -> new ReplayFrame(Long.parseLong(value.toString()), old.x(), old.y(), old.keys());
                    case 2 -> new ReplayFrame(old.timeMs(), Double.parseDouble(value.toString()), old.y(), old.keys());
                    case 3 -> new ReplayFrame(old.timeMs(), old.x(), Double.parseDouble(value.toString()), old.keys());
                    case 4 -> new ReplayFrame(old.timeMs(), old.x(), old.y(),
                            parseKeyMask(value.toString()));
                    default -> old;
                };
                List<ReplayFrame> candidate = new ArrayList<>(frames);
                candidate.set(row, replacement);
                ReplayWriter.validate(candidate);
                remember();
                frames.set(row, replacement);
                changed("Updated frame " + (row + 1) + ".");
            } catch (IOException | NumberFormatException exception) {
                status.setText("Edit rejected: " + exception.getMessage());
                fireTableCellUpdated(row, column);
            }
        }

        private int parseKeyMask(String value) {
            long parsed = Long.parseUnsignedLong(value);
            if (parsed > 0xffff_ffffL) throw new NumberFormatException("key mask exceeds uint32");
            return (int) parsed;
        }
    }

    private final class EditorCanvas extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int TIMELINE_HEIGHT = 138;
        private static final int[] LANE_BITS = {1, 2, 4, 8};
        private static final String[] LANE_NAMES = {"M1", "M2", "K1", "K2"};
        private static final Color[] LANE_COLORS = {
                new Color(77, 166, 255), new Color(88, 212, 176),
                new Color(187, 107, 217), new Color(242, 153, 74)};
        private java.awt.Rectangle playfield = new java.awt.Rectangle();
        private java.awt.Rectangle timeline = new java.awt.Rectangle();
        private java.awt.Rectangle overview = new java.awt.Rectangle();
        private double viewX;
        private double viewY;
        private double viewWidth = 512;
        private double viewHeight = 384;
        private boolean draggingPath;
        private boolean draggingInputSelection;
        private int dragStartX;
        private int dragStartY;
        private int hoverX = -1;
        private int hoverY = -1;
        private BrushMode activeBrushMode = BrushMode.MOVE;
        private List<ReplayFrame> dragOriginal;
        private int inputSelectionAnchorX;
        private int pendingInputLane = -1;
        private long inputSelectionAnchorMs;
        private Long inputSelectionStartMs;
        private Long inputSelectionEndMs;

        EditorCanvas() {
            setBackground(new Color(15, 15, 15));
            setPreferredSize(new Dimension(900, 530));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) { pressed(event); }
                @Override public void mouseDragged(MouseEvent event) { dragged(event); }
                @Override public void mouseReleased(MouseEvent event) { released(event); }
                @Override public void mouseMoved(MouseEvent event) { moved(event); }
                @Override public void mouseExited(MouseEvent event) { hoverX = -1; hoverY = -1; repaint(); }
                @Override public void mouseWheelMoved(MouseWheelEvent event) { wheel(event); }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            layoutRegions();
            drawOverview(g);
            drawPlayfield(g);
            drawTimeline(g);
            g.dispose();
        }

        private void layoutRegions() {
            int availableHeight = Math.max(220, getHeight() - TIMELINE_HEIGHT - 78);
            int fieldHeight = Math.min(availableHeight, (getWidth() - 100) * 3 / 4);
            int fieldWidth = fieldHeight * 4 / 3;
            int x = (getWidth() - fieldWidth) / 2;
            overview.setBounds(55, 8, Math.max(100, getWidth() - 110), 36);
            playfield.setBounds(x, 55, fieldWidth, fieldHeight);
            timeline.setBounds(55, getHeight() - TIMELINE_HEIGHT, Math.max(100, getWidth() - 75), TIMELINE_HEIGHT - 8);
            if (!draggingPath) updateCoordinateViewport();
        }

        private void updateCoordinateViewport() {
            double minimumX = 0;
            double maximumX = 512;
            double minimumY = 0;
            double maximumY = 384;
            long start = visibleStart();
            long end = visibleEnd();
            for (ReplayFrame frame : frames) {
                if (frame.timeMs() < start) continue;
                if (frame.timeMs() > end) break;
                minimumX = Math.min(minimumX, frame.x());
                maximumX = Math.max(maximumX, frame.x());
                minimumY = Math.min(minimumY, frame.y());
                maximumY = Math.max(maximumY, frame.y());
            }
            if (beatmap != null) {
                long preempt = beatmap.preemptMs();
                for (BeatmapData.HitObject object : beatmap.hitObjects()) {
                    if (object.startTime() - preempt > end) break;
                    if (object.endTime() + 240 < start) continue;
                    minimumX = Math.min(minimumX, object.x());
                    maximumX = Math.max(maximumX, object.x());
                    minimumY = Math.min(minimumY, object.y());
                    maximumY = Math.max(maximumY, object.y());
                    for (Point2D.Double point : object.path()) {
                        minimumX = Math.min(minimumX, point.x);
                        maximumX = Math.max(maximumX, point.x);
                        minimumY = Math.min(minimumY, point.y);
                        maximumY = Math.max(maximumY, point.y);
                    }
                }
            }
            if (minimumX < 0) minimumX -= 24;
            if (maximumX > 512) maximumX += 24;
            if (minimumY < 0) minimumY -= 24;
            if (maximumY > 384) maximumY += 24;

            double width = maximumX - minimumX;
            double height = maximumY - minimumY;
            double targetRatio = 4.0 / 3.0;
            if (width / height > targetRatio) {
                double expandedHeight = width / targetRatio;
                minimumY -= (expandedHeight - height) / 2;
                height = expandedHeight;
            } else {
                double expandedWidth = height * targetRatio;
                minimumX -= (expandedWidth - width) / 2;
                width = expandedWidth;
            }
            viewX = minimumX;
            viewY = minimumY;
            viewWidth = width;
            viewHeight = height;
        }

        private void drawOverview(Graphics2D g) {
            g.setColor(new Color(23, 23, 23));
            g.fillRoundRect(overview.x, overview.y, overview.width, overview.height, 9, 9);
            long first = frames.getFirst().timeMs();
            long last = Math.max(first + 1, frames.getLast().timeMs());
            if (analysis != null) {
                for (int index = 0; index < analysis.results().size(); index++) {
                    ReplayAnalysis.Result result = analysis.results().get(index);
                    if (result.judgement() == ReplayAnalysis.Judgement.HIT_300) continue;
                    int x = overview.x + (int) Math.round((result.object().startTime() - first)
                            / (double) (last - first) * overview.width);
                    g.setColor(judgementColor(index, 190));
                    g.drawLine(x, overview.y + 5, x, overview.y + overview.height - 5);
                }
                g.setColor(new Color(231, 76, 60, 210));
                for (ReplayAnalysis.SliderCheckpoint checkpoint : analysis.sliderCheckpoints()) {
                    if (checkpoint.hit()) continue;
                    int x = overview.x + (int) Math.round((checkpoint.time() - first)
                            / (double) (last - first) * overview.width);
                    g.drawLine(x, overview.y + 8, x, overview.y + overview.height - 8);
                }
            }
            int viewStart = overview.x + (int) Math.round((visibleStart() - first)
                    / (double) (last - first) * overview.width);
            int viewEnd = overview.x + (int) Math.round((visibleEnd() - first)
                    / (double) (last - first) * overview.width);
            viewStart = Math.max(overview.x, Math.min(overview.x + overview.width, viewStart));
            viewEnd = Math.max(overview.x, Math.min(overview.x + overview.width, viewEnd));
            g.setColor(new Color(255, 255, 255, 35));
            g.fillRoundRect(Math.min(viewStart, viewEnd), overview.y + 2,
                    Math.max(3, Math.abs(viewEnd - viewStart)), overview.height - 4, 7, 7);
            int playX = overview.x + (int) Math.round((playheadMs - first)
                    / (double) (last - first) * overview.width);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.drawLine(playX, overview.y + 2, playX, overview.y + overview.height - 2);
        }

        private long visibleStart() {
            return playheadMs - ((Number) windowSize.getValue()).longValue() / 2;
        }

        private long visibleEnd() {
            return visibleStart() + ((Number) windowSize.getValue()).longValue();
        }

        private void drawPlayfield(Graphics2D g) {
            g.setColor(new Color(9, 9, 9));
            g.fillRoundRect(playfield.x, playfield.y, playfield.width, playfield.height, 12, 12);
            g.setClip(playfield);
            int officialLeft = toScreenX(0);
            int officialTop = toScreenY(0);
            int officialRight = toScreenX(512);
            int officialBottom = toScreenY(384);
            g.setColor(Color.BLACK);
            g.fillRect(officialLeft, officialTop, officialRight - officialLeft,
                    officialBottom - officialTop);
            drawHitObjects(g);
            long start = visibleStart();
            long end = visibleEnd();
            int previous = -1;
            for (int index = 0; index < frames.size(); index++) {
                ReplayFrame frame = frames.get(index);
                if (frame.timeMs() < start) continue;
                if (frame.timeMs() > end) break;
                if (previous >= 0) {
                    ReplayFrame from = frames.get(previous);
                    g.setColor(keyColor(from.keys()));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(toScreenX(from.x()), toScreenY(from.y()), toScreenX(frame.x()), toScreenY(frame.y()));
                }
                previous = index;
            }
            ReplayFrame cursor = interpolatedFrame(playheadMs);
            g.setColor(Color.WHITE);
            int cx = toScreenX(cursor.x());
            int cy = toScreenY(cursor.y());
            g.setStroke(new BasicStroke(2));
            g.drawOval(cx - 7, cy - 7, 14, 14);
            g.setClip(null);
            if (hoverX >= 0 && playfield.contains(hoverX, hoverY)) {
                BrushMode visibleMode = activeBrushMode;
                g.setColor(visibleMode == BrushMode.SQUISH
                        ? new Color(255, 145, 80, 180) : new Color(255, 255, 255, 120));
                int radius = (Integer) brushSize.getValue();
                int screenRadius = (int) Math.round(radius * coordinateScale());
                g.drawOval(hoverX - screenRadius / 2, hoverY - screenRadius / 2,
                        screenRadius, screenRadius);
                if (visibleMode == BrushMode.SQUISH) {
                    g.drawLine(hoverX - 8, hoverY, hoverX + 8, hoverY);
                    g.drawLine(hoverX, hoverY - 8, hoverX, hoverY + 8);
                }
            }
            g.setColor(new Color(90, 90, 90));
            g.drawRoundRect(playfield.x, playfield.y, playfield.width, playfield.height, 12, 12);
            g.setColor(new Color(145, 145, 145));
            g.drawRect(officialLeft, officialTop, officialRight - officialLeft,
                    officialBottom - officialTop);
            g.setColor(new Color(220, 220, 220));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            String viewportLabel = viewWidth > 512.001 || viewHeight > 384.001
                    ? "Outlined 512 × 384 playfield — viewport expanded for off-playfield cursor data"
                    : "512 × 384 playfield — Move drags; Squish contracts toward the brush (Alt temporarily squishes)";
            g.drawString(viewportLabel,
                    playfield.x + 8, playfield.y + 18);
        }

        private void drawHitObjects(Graphics2D g) {
            if (beatmap == null) return;
            long preempt = beatmap.preemptMs();
            long fadeIn = beatmap.fadeInMs();
            boolean hidden = document.data().hasHidden();
            double scale = coordinateScale();
            int radius = Math.max(4, (int) Math.round(beatmap.circleRadius() * scale));
            for (int objectIndex = 0; objectIndex < beatmap.hitObjects().size(); objectIndex++) {
                BeatmapData.HitObject object = beatmap.hitObjects().get(objectIndex);
                if (object.startTime() - preempt > playheadMs) break;
                long visualEnd = object.endTime();
                if (object.type() == BeatmapData.ObjectType.CIRCLE && analysis != null
                        && objectIndex < analysis.results().size()) {
                    ReplayAnalysis.Result result = analysis.results().get(objectIndex);
                    visualEnd = result.headHit()
                            ? object.startTime() + result.errorMs()
                            : object.startTime() + (long) Math.ceil(
                            Math.floor(200 - 10 * beatmap.overallDifficulty()) - 0.5);
                }
                if (playheadMs > visualEnd + 240) continue;
                double progress = (playheadMs - (object.startTime() - preempt))
                        / (double) Math.max(1, fadeIn);
                int alpha = (int) Math.round(235 * Math.max(0, Math.min(1, progress)));
                if (hidden && object.type() != BeatmapData.ObjectType.SPINNER) {
                    long appearance = object.startTime() - preempt;
                    long hiddenFadeIn = object.type() == BeatmapData.ObjectType.SLIDER
                            ? fadeIn : Math.round(preempt * 0.4);
                    long fadeOutStart = appearance + hiddenFadeIn;
                    long fadeOutDuration = object.type() == BeatmapData.ObjectType.SLIDER
                            ? Math.max(1, object.endTime() - fadeOutStart)
                            : Math.max(1, Math.round(preempt * 0.3));
                    double fadeInProgress = (playheadMs - appearance)
                            / (double) Math.max(1, hiddenFadeIn);
                    double fadeOutProgress = (playheadMs - fadeOutStart)
                            / (double) fadeOutDuration;
                    double hiddenAlpha = Math.max(0, Math.min(1, fadeInProgress));
                    if (playheadMs >= fadeOutStart)
                        hiddenAlpha *= Math.max(0, 1 - fadeOutProgress);
                    alpha = (int) Math.round(235 * hiddenAlpha);
                }
                if (playheadMs > visualEnd) {
                    alpha = (int) Math.round(alpha * Math.max(0,
                            1.0 - (playheadMs - visualEnd) / 240.0));
                }
                alpha = Math.max(0, Math.min(255, alpha));
                int x = toScreenX(object.x());
                int y = toScreenY(object.y());

                if (object.type() == BeatmapData.ObjectType.SPINNER) {
                    g.setColor(new Color(100, 150, 230, Math.max(35, alpha / 2)));
                    int spinnerRadius = Math.min(playfield.width, playfield.height) / 3;
                    g.fillOval(playfield.x + playfield.width / 2 - spinnerRadius,
                            playfield.y + playfield.height / 2 - spinnerRadius,
                            spinnerRadius * 2, spinnerRadius * 2);
                    g.setColor(judgementColor(objectIndex, alpha));
                    g.drawString("SPINNER", playfield.x + playfield.width / 2 - 30,
                            playfield.y + playfield.height / 2);
                    continue;
                }

                if (object.type() == BeatmapData.ObjectType.SLIDER && object.path().size() > 1) {
                    Path2D.Double slider = new Path2D.Double();
                    Point2D.Double first = object.path().getFirst();
                    slider.moveTo(toScreenX(first.x), toScreenY(first.y));
                    for (int index = 1; index < object.path().size(); index++) {
                        Point2D.Double point = object.path().get(index);
                        slider.lineTo(toScreenX(point.x), toScreenY(point.y));
                    }
                    g.setColor(new Color(45, 45, 55, Math.max(80, alpha)));
                    g.setStroke(new BasicStroke(radius * 2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(slider);
                    g.setColor(judgementColor(objectIndex, alpha));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(slider);
                }

                g.setColor(new Color(35, 35, 42, Math.max(90, alpha)));
                g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                g.setColor(judgementColor(objectIndex, alpha));
                g.setStroke(new BasicStroke(Math.max(2, radius / 8f)));
                g.drawOval(x - radius, y - radius, radius * 2, radius * 2);

                if (!hidden && playheadMs < object.startTime()) {
                    double remaining = (object.startTime() - playheadMs) / (double) Math.max(1, preempt);
                    int approachRadius = radius + (int) Math.round(radius * 3.0 * Math.max(0, remaining));
                    g.setStroke(new BasicStroke(2));
                    g.drawOval(x - approachRadius, y - approachRadius,
                            approachRadius * 2, approachRadius * 2);
                }

                if (object.type() == BeatmapData.ObjectType.SLIDER)
                    drawSliderBall(g, object, radius, alpha);
            }
        }

        private void drawSliderBall(Graphics2D g, BeatmapData.HitObject slider,
                                    int radius, int alpha) {
            if (playheadMs < slider.startTime() || playheadMs > slider.endTime()
                    || slider.path().isEmpty()) return;
            long duration = slider.endTime() - slider.startTime();
            if (duration <= 0) return;

            int spans = Math.max(1, slider.repeatCount());
            double totalProgress = (playheadMs - slider.startTime()) / (double) duration * spans;
            int span = Math.min(spans - 1, (int) Math.floor(totalProgress));
            double spanProgress = span == spans - 1 && playheadMs == slider.endTime()
                    ? 1.0 : totalProgress - span;
            if ((span & 1) != 0) spanProgress = 1.0 - spanProgress;
            Point2D.Double point = pointAlongPath(slider.path(), spanProgress);
            int x = toScreenX(point.x);
            int y = toScreenY(point.y);
            int ballRadius = Math.max(5, radius * 2 / 3);
            int followRadius = Math.max(ballRadius + 4, radius);

            g.setColor(new Color(255, 255, 255, Math.max(45, alpha / 2)));
            g.setStroke(new BasicStroke(Math.max(2f, radius / 10f)));
            g.drawOval(x - followRadius, y - followRadius, followRadius * 2, followRadius * 2);
            g.setColor(new Color(35, 35, 42, Math.max(170, alpha)));
            g.fillOval(x - ballRadius, y - ballRadius, ballRadius * 2, ballRadius * 2);
            g.setColor(new Color(255, 255, 255, Math.max(180, alpha)));
            g.setStroke(new BasicStroke(Math.max(2f, ballRadius / 5f)));
            g.drawOval(x - ballRadius, y - ballRadius, ballRadius * 2, ballRadius * 2);
        }

        private Point2D.Double pointAlongPath(List<Point2D.Double> path, double progress) {
            if (path.size() == 1) return path.getFirst();
            double length = 0;
            for (int index = 1; index < path.size(); index++)
                length += path.get(index - 1).distance(path.get(index));
            double target = Math.max(0, Math.min(1, progress)) * length;
            double travelled = 0;
            for (int index = 1; index < path.size(); index++) {
                Point2D.Double from = path.get(index - 1);
                Point2D.Double to = path.get(index);
                double segment = from.distance(to);
                if (travelled + segment >= target && segment > 0) {
                    double ratio = (target - travelled) / segment;
                    return new Point2D.Double(from.x + (to.x - from.x) * ratio,
                            from.y + (to.y - from.y) * ratio);
                }
                travelled += segment;
            }
            return path.getLast();
        }

        private Color judgementColor(int objectIndex, int alpha) {
            if (analysis == null || objectIndex >= analysis.results().size())
                return new Color(255, 255, 255, alpha);
            return switch (analysis.results().get(objectIndex).judgement()) {
                case HIT_300 -> new Color(90, 160, 255, alpha);
                case HIT_100 -> new Color(106, 176, 76, alpha);
                case HIT_50 -> new Color(241, 196, 15, alpha);
                case MISS -> new Color(231, 76, 60, alpha);
            };
        }

        private void drawTimeline(Graphics2D g) {
            g.setColor(new Color(23, 23, 23));
            g.fillRoundRect(timeline.x, timeline.y, timeline.width, timeline.height, 10, 10);
            long start = visibleStart();
            long end = visibleEnd();
            int header = 25;
            drawEvaluationMarkers(g, start, end, header);
            int laneHeight = (timeline.height - header - 6) / 4;
            for (int lane = 0; lane < 4; lane++) {
                int y = timeline.y + header + lane * laneHeight;
                g.setColor(new Color(40, 40, 40));
                g.fillRoundRect(timeline.x + 34, y + 2, timeline.width - 40, laneHeight - 4, 6, 6);
                g.setColor(new Color(205, 205, 205));
                g.drawString(LANE_NAMES[lane], timeline.x + 7, y + laneHeight - 8);
                g.setColor(LANE_COLORS[lane]);
                int segmentStart = -1;
                for (ReplayFrame frame : frames) {
                    if (frame.timeMs() > end) break;
                    boolean down = (frame.keys() & LANE_BITS[lane]) != 0;
                    int x = timeToX(frame.timeMs());
                    if (down && segmentStart < 0) segmentStart = Math.max(timeline.x + 34, x);
                    if (!down && segmentStart >= 0) {
                        g.fillRoundRect(segmentStart, y + 5, Math.max(2, x - segmentStart), laneHeight - 10, 5, 5);
                        segmentStart = -1;
                    }
                }
                if (segmentStart >= 0)
                    g.fillRoundRect(segmentStart, y + 5, timeline.x + timeline.width - segmentStart - 5,
                            laneHeight - 10, 5, 5);
            }
            drawInputSelection(g, header);
            int playX = timeToX(playheadMs);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.drawLine(playX, timeline.y + 3, playX, timeline.y + timeline.height - 3);
            g.drawString(formatTime(playheadMs), timeline.x + 8, timeline.y + 17);
            String evaluation = analysis == null ? ""
                    : String.format(Locale.ROOT, "ACC %.2f%%  •  %dx  •  ",
                    analysis.accuracy() * 100, analysis.comboAt(playheadMs));
            g.drawString(evaluation + "wheel: zoom • header: scrub • click lane: toggle • drag lane: select",
                    timeline.x + 145, timeline.y + 17);
        }

        private void drawInputSelection(Graphics2D g, int header) {
            if (!hasInputSelection()) return;
            int laneLeft = timeline.x + 34;
            int laneRight = timeline.x + timeline.width - 6;
            int left = Math.max(laneLeft, Math.min(laneRight, timeToX(inputSelectionStart())));
            int right = Math.max(laneLeft, Math.min(laneRight, timeToX(inputSelectionEnd())));
            int top = timeline.y + header;
            int height = timeline.height - header - 3;
            g.setColor(new Color(255, 255, 255, 38));
            g.fillRect(left, top, Math.max(1, right - left), height);
            g.setColor(new Color(255, 255, 255, 190));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(left, top, Math.max(1, right - left), height);
        }

        private void drawEvaluationMarkers(Graphics2D g, long start, long end, int header) {
            if (analysis == null) return;
            for (ReplayAnalysis.Result result : analysis.results()) {
                long time = result.object().startTime();
                if (time < start) continue;
                if (time > end) break;
                g.setColor(switch (result.judgement()) {
                    case HIT_300 -> new Color(90, 160, 255);
                    case HIT_100 -> new Color(106, 176, 76);
                    case HIT_50 -> new Color(241, 196, 15);
                    case MISS -> new Color(231, 76, 60);
                });
                int x = timeToX(time);
                int width = 9;
                if (result.object().type() == BeatmapData.ObjectType.SLIDER) {
                    width = Math.max(9, timeToX(result.object().endTime()) - x);
                }
                int height = result.judgement() == ReplayAnalysis.Judgement.MISS ? 12 : 9;
                g.fillRoundRect(x - 4, timeline.y + 5, width, height, 5, 5);
            }
            for (ReplayAnalysis.SliderCheckpoint checkpoint : analysis.sliderCheckpoints()) {
                if (checkpoint.time() < start) continue;
                if (checkpoint.time() > end) break;
                int x = timeToX(checkpoint.time());
                g.setColor(checkpoint.hit() ? new Color(90, 200, 130) : new Color(231, 76, 60));
                int size = checkpoint.repeat() ? 8 : 6;
                g.fillRect(x - size / 2, timeline.y + header - size - 2, size, size);
            }
        }

        private Color keyColor(int keys) {
            if ((keys & 4) != 0 && (keys & 8) != 0) return new Color(235, 82, 112);
            if ((keys & 4) != 0) return LANE_COLORS[2];
            if ((keys & 8) != 0) return LANE_COLORS[3];
            if ((keys & 3) != 0) return new Color(80, 175, 230);
            return new Color(175, 175, 175);
        }

        private void pressed(MouseEvent event) {
            if (previewTimer.isRunning()) togglePreview();
            if (playfield.contains(event.getPoint()) && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                draggingPath = true;
                dragStartX = event.getX();
                dragStartY = event.getY();
                hoverX = event.getX();
                hoverY = event.getY();
                activeBrushMode = event.isAltDown() ? BrushMode.SQUISH
                        : (BrushMode) brushMode.getSelectedItem();
                dragOriginal = List.copyOf(frames);
                remember();
                if (activeBrushMode == BrushMode.SQUISH) applyPathStroke(event.getX(), event.getY());
                return;
            }
            if (overview.contains(event.getPoint())) {
                long first = frames.getFirst().timeMs();
                long last = frames.getLast().timeMs();
                double rate = (event.getX() - overview.x) / (double) overview.width;
                playheadMs = first + Math.round(Math.max(0, Math.min(1, rate)) * (last - first));
                repaint();
                return;
            }
            if (!timeline.contains(event.getPoint())) return;
            long clickedTime = xToTime(event.getX());
            int header = 25;
            if (event.getY() >= timeline.y + header) {
                int laneHeight = (timeline.height - header - 6) / 4;
                int lane = Math.min(3, (event.getY() - timeline.y - header) / laneHeight);
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    draggingInputSelection = true;
                    inputSelectionAnchorX = event.getX();
                    inputSelectionAnchorMs = clickedTime;
                    inputSelectionStartMs = clickedTime;
                    inputSelectionEndMs = clickedTime;
                    pendingInputLane = lane;
                } else if (javax.swing.SwingUtilities.isRightMouseButton(event)) {
                    applyInputBlock(lane, clickedTime, false);
                }
            } else {
                playheadMs = clickedTime;
            }
            repaint();
        }

        private void dragged(MouseEvent event) {
            if (draggingInputSelection) {
                long current = xToTime(event.getX());
                inputSelectionStartMs = Math.min(inputSelectionAnchorMs, current);
                inputSelectionEndMs = Math.max(inputSelectionAnchorMs, current);
                repaint();
                return;
            }
            if (draggingPath && dragOriginal != null) {
                hoverX = event.getX();
                hoverY = event.getY();
                applyPathStroke(event.getX(), event.getY());
            }
        }

        private void moved(MouseEvent event) {
            hoverX = event.getX();
            hoverY = event.getY();
            activeBrushMode = event.isAltDown() ? BrushMode.SQUISH
                    : (BrushMode) brushMode.getSelectedItem();
            repaint();
        }

        private void applyPathStroke(int targetScreenX, int targetScreenY) {
            double dx = (targetScreenX - dragStartX) / coordinateScale();
            double dy = (targetScreenY - dragStartY) / coordinateScale();
            double centerX = screenToCoordinateX(dragStartX);
            double centerY = screenToCoordinateY(dragStartY);
            double targetX = screenToCoordinateX(targetScreenX);
            double targetY = screenToCoordinateY(targetScreenY);
            double radius = ((Number) brushSize.getValue()).doubleValue() / 2.0;
            double strength = ((Number) brushStrength.getValue()).doubleValue() / 100.0;
            long start = visibleStart();
            long end = visibleEnd();
            for (int index = 0; index < frames.size(); index++) {
                ReplayFrame original = dragOriginal.get(index);
                if (original.timeMs() < start || original.timeMs() > end) continue;
                double distance = Math.hypot(original.x() - centerX, original.y() - centerY);
                if (distance > radius) continue;
                double weight = 1.0 - distance / radius;
                double x;
                double y;
                if (activeBrushMode == BrushMode.SQUISH) {
                    x = original.x() + (targetX - original.x()) * weight * strength;
                    y = original.y() + (targetY - original.y()) * weight * strength;
                } else {
                    x = original.x() + dx * weight;
                    y = original.y() + dy * weight;
                }
                frames.set(index, new ReplayFrame(original.timeMs(),
                        clamp(x, -1024, 1536), clamp(y, -1024, 1408), original.keys()));
            }
            tableModel.fireTableDataChanged();
            repaint();
        }

        private void released(MouseEvent event) {
            if (draggingInputSelection) {
                draggingInputSelection = false;
                if (Math.abs(event.getX() - inputSelectionAnchorX) < 4) {
                    inputSelectionStartMs = null;
                    inputSelectionEndMs = null;
                    reverseInputsButton.setEnabled(false);
                    applyInputBlock(pendingInputLane, inputSelectionAnchorMs, true);
                } else {
                    long current = xToTime(event.getX());
                    inputSelectionStartMs = Math.min(inputSelectionAnchorMs, current);
                    inputSelectionEndMs = Math.max(inputSelectionAnchorMs, current);
                    reverseInputsButton.setEnabled(hasInputSelection());
                    status.setText("Selected input from " + formatTime(inputSelectionStart())
                            + " to " + formatTime(inputSelectionEnd())
                            + ". Use Reverse inputs to swap it to the other key.");
                }
                pendingInputLane = -1;
                repaint();
                return;
            }
            if (draggingPath) {
                draggingPath = false;
                dragOriginal = null;
                refreshAnalysis();
                status.setText((activeBrushMode == BrushMode.SQUISH
                        ? "Squished nearby aim toward the brush." : "Moved the cursor path.")
                        + " Ctrl+Z undoes the stroke.");
            }
        }

        private void wheel(MouseWheelEvent event) {
            if (!timeline.contains(event.getPoint()) && !playfield.contains(event.getPoint())) return;
            int current = (Integer) windowSize.getValue();
            int next = Math.max(400, Math.min(10_000, current + event.getWheelRotation() * 100));
            windowSize.setValue(next);
        }

        private void applyInputBlock(int lane, long time, boolean pressed) {
            int bit = LANE_BITS[lane];
            long duration = ((Number) inputBlockSize.getValue()).longValue();
            remember();
            List<ReplayFrame> edited = ReplayEdits.applyCenteredInputBlock(
                    frames, time, duration, bit, pressed);
            frames.clear();
            frames.addAll(edited);
            changed((pressed ? "Added " : "Removed ") + LANE_NAMES[lane]
                    + " input block centered at " + time + " ms (" + duration + " ms)." );
        }

        private boolean hasInputSelection() {
            return inputSelectionStartMs != null && inputSelectionEndMs != null
                    && inputSelectionEndMs > inputSelectionStartMs;
        }

        private long inputSelectionStart() {
            if (!hasInputSelection()) throw new IllegalStateException("No input selection");
            return inputSelectionStartMs;
        }

        private long inputSelectionEnd() {
            if (!hasInputSelection()) throw new IllegalStateException("No input selection");
            return inputSelectionEndMs;
        }

        private ReplayFrame interpolatedFrame(long time) {
            int index = nearestFrame(time);
            ReplayFrame first = frames.get(index);
            if (first.timeMs() > time && index > 0) first = frames.get(--index);
            if (index + 1 >= frames.size()) return first;
            ReplayFrame second = frames.get(index + 1);
            long span = second.timeMs() - first.timeMs();
            if (span <= 0) return first;
            double rate = Math.max(0, Math.min(1, (time - first.timeMs()) / (double) span));
            return new ReplayFrame(time, first.x() + (second.x() - first.x()) * rate,
                    first.y() + (second.y() - first.y()) * rate, first.keys());
        }

        private double coordinateScale() { return playfield.width / viewWidth; }
        private int toScreenX(double x) {
            return playfield.x + (int) Math.round((x - viewX) * coordinateScale());
        }
        private int toScreenY(double y) {
            return playfield.y + (int) Math.round((y - viewY) * coordinateScale());
        }
        private double screenToCoordinateX(int x) {
            return viewX + (x - playfield.x) / coordinateScale();
        }
        private double screenToCoordinateY(int y) {
            return viewY + (y - playfield.y) / coordinateScale();
        }
        private int timeToX(long time) {
            return timeline.x + 34 + (int) Math.round((time - visibleStart())
                    / (double) (visibleEnd() - visibleStart()) * (timeline.width - 40));
        }
        private long xToTime(int x) {
            double rate = (x - timeline.x - 34) / (double) (timeline.width - 40);
            return Math.max(frames.getFirst().timeMs(), Math.min(frames.getLast().timeMs(),
                    visibleStart() + Math.round(rate * (visibleEnd() - visibleStart()))));
        }
        private double clamp(double value, double minimum, double maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
        private String formatTime(long time) {
            long safe = Math.max(0, time);
            return String.format(Locale.ROOT, "%02d:%02d.%03d", safe / 60_000,
                    safe / 1000 % 60, safe % 1000);
        }
    }
}
