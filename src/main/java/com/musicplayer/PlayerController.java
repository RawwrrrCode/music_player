package com.musicplayer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;

import javax.sound.sampled.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerController {

    @FXML private VBox      rootPane;
    @FXML private StackPane albumArtPane;
    @FXML private ImageView albumArtView;
    @FXML private Label     musicNoteLabel;
    @FXML private Label     songTitle;
    @FXML private Label     artistLabel;
    @FXML private Slider    progressSlider;
    @FXML private Label     currentTime;
    @FXML private Label     totalTime;
    @FXML private Button    playPauseBtn;
    @FXML private Button    shuffleBtn;
    @FXML private Button    repeatBtn;
    @FXML private Slider    volumeSlider;
    @FXML private Label     volumeLabel;
    @FXML private TextField searchField;
    @FXML private TabPane   tabPane;
    @FXML private ListView<Song> songList;
    @FXML private ListView<Song> queueList;
    @FXML private ListView<Song> recentList;
    @FXML private Label     lyricsLabel;

    private Thread playerThread;
    private SourceDataLine audioLine;
    private volatile boolean stopRequested = false;
    private volatile boolean isPaused      = false;
    private boolean isPlaying              = false;
    private int elapsedSeconds             = 0;
    private Timeline countTimer;

    private final ObservableList<Song> playlist     = FXCollections.observableArrayList();
    private final ObservableList<Song> queue        = FXCollections.observableArrayList();
    private final ObservableList<Song> recentPlayed = FXCollections.observableArrayList();
    private FilteredList<Song> filteredPlaylist;

    private Song currentlyPlayingSong = null;
    private Song draggedSong          = null;

    private byte[] currentFileBytes = null;
    private int    totalDurationSec = 0;
    private volatile boolean userDragging   = false;
    private volatile int     playGeneration = 0;

    private boolean    shuffleMode   = false;
    private List<Song> originalOrder = new ArrayList<>();

    private enum RepeatMode { NONE, ONE, ALL }
    private RepeatMode repeatMode = RepeatMode.NONE;

    // Mini player
    private Stage       miniStage    = null;
    private Label       miniSongLbl  = null;
    private Button      miniPlayBtn  = null;
    private ProgressBar miniProgress = null;

    private static final String PLAYLIST_FILE =
            System.getProperty("user.home") + "/.musicplayer_playlist.txt";
    private static final String CSS_OFF = "btn-secondary";
    private static final String CSS_ON  = "btn-secondary-on";

    private static final String ALBUM_ART_STYLE =
            "-fx-background-color: #181832; -fx-background-radius: 14; " +
            "-fx-effect: dropshadow(gaussian,rgba(124,58,237,0.65),42,0.3,0,8); " +
            "-fx-border-color: rgba(124,58,237,0.2); -fx-border-radius: 14; -fx-border-width: 1;";

    // ─────────────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        // Search / filter
        filteredPlaylist = new FilteredList<>(playlist, s -> true);
        songList.setItems(filteredPlaylist);
        queueList.setItems(queue);
        recentList.setItems(recentPlayed);

        searchField.textProperty().addListener((obs, old, q) -> {
            String query = q.trim().toLowerCase();
            filteredPlaylist.setPredicate(s -> query.isEmpty()
                || s.getTitle().toLowerCase().contains(query)
                || (s.getArtist() != null && s.getArtist().toLowerCase().contains(query)));
        });

        // Double-click playlist → play
        songList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) { elapsedSeconds = 0; playSelected(); }
        });

        // Double-click queue → play immediately
        queueList.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            Song s = queueList.getSelectionModel().getSelectedItem();
            if (s == null) return;
            queue.remove(s);
            int idx = playlist.indexOf(s);
            if (idx >= 0) songList.getSelectionModel().select(filteredPlaylist.indexOf(s));
            else { playlist.add(s); songList.getSelectionModel().select(filteredPlaylist.size() - 1); }
            elapsedSeconds = 0; tabPane.getSelectionModel().select(0); playSelected();
        });

        // Double-click recent → play
        recentList.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            Song s = recentList.getSelectionModel().getSelectedItem();
            if (s == null) return;
            int idx = filteredPlaylist.indexOf(s);
            if (idx >= 0) {
                songList.getSelectionModel().select(idx);
                elapsedSeconds = 0; tabPane.getSelectionModel().select(0); playSelected();
            }
        });

        volumeSlider.valueProperty().addListener((obs, old, v) -> {
            volumeLabel.setText((int)(v.doubleValue() * 100) + "%");
            setVolume(v.floatValue());
        });

        // ── Playlist cell: highlight + cloud + drag + context menu ────────
        songList.setCellFactory(lv -> {
            ListCell<Song> cell = new ListCell<>() {
                @Override
                protected void updateItem(Song song, boolean empty) {
                    super.updateItem(song, empty);
                    if (empty || song == null) { setText(null); setStyle(""); setContextMenu(null); return; }
                    boolean playing = song.equals(currentlyPlayingSong);
                    String prefix = playing ? "♪  " : (song.isCloud() ? "☁  " : "    ");
                    String artistSuffix = (song.getArtist() != null && !song.getArtist().isEmpty())
                        ? "  —  " + song.getArtist() : "";
                    setText(prefix + song.getTitle() + artistSuffix);
                    setStyle(playing ? "-fx-text-fill: #a78bfa; -fx-font-weight: bold;"
                                     : song.isCloud() ? "-fx-text-fill: #38bdf8;" : "");

                    ContextMenu cm = new ContextMenu();
                    MenuItem playNow  = new MenuItem("▶  Putar Sekarang");
                    MenuItem addQueue = new MenuItem("⊕  Tambah ke Queue");
                    playNow.setOnAction(ev -> {
                        songList.getSelectionModel().select(getItem());
                        elapsedSeconds = 0; stopSong(); playSelected();
                    });
                    addQueue.setOnAction(ev -> {
                        if (getItem() != null) { queue.add(getItem()); tabPane.getSelectionModel().select(1); }
                    });
                    cm.getItems().addAll(playNow, new SeparatorMenuItem(), addQueue);
                    setContextMenu(cm);
                }
            };

            // Drag to reorder
            cell.setOnDragDetected(e -> {
                if (cell.getItem() == null) return;
                draggedSong = cell.getItem();
                Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(cell.getItem().getTitle());
                db.setContent(cc); e.consume();
            });
            cell.setOnDragOver(e -> {
                if (draggedSong != null && cell.getItem() != null && !cell.getItem().equals(draggedSong))
                    e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            cell.setOnDragDropped(e -> {
                if (draggedSong == null || cell.getItem() == null) { e.setDropCompleted(false); return; }
                int from = playlist.indexOf(draggedSong), to = playlist.indexOf(cell.getItem());
                if (from >= 0 && to >= 0 && from != to) {
                    playlist.remove(from); playlist.add(to, draggedSong);
                    savePlaylist();
                }
                draggedSong = null; e.setDropCompleted(true); e.consume();
            });
            cell.setOnDragDone(e -> draggedSong = null);
            return cell;
        });

        queueList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Song song, boolean empty) {
                super.updateItem(song, empty);
                if (empty || song == null) { setText(null); setContextMenu(null); return; }
                setText((song.isCloud() ? "☁  " : "⊕  ") + song.getTitle());
                setStyle(song.isCloud() ? "-fx-text-fill: #38bdf8;" : "-fx-text-fill: #9ca3af;");
                ContextMenu cm = new ContextMenu();
                MenuItem rm = new MenuItem("✕  Hapus dari Queue");
                rm.setOnAction(e -> queue.remove(getItem()));
                cm.getItems().add(rm); setContextMenu(cm);
            }
        });

        recentList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Song song, boolean empty) {
                super.updateItem(song, empty);
                if (empty || song == null) { setText(null); return; }
                setText((song.isCloud() ? "☁  " : "⟳  ") + song.getTitle());
                setStyle(song.isCloud() ? "-fx-text-fill: #38bdf8;" : "-fx-text-fill: #9ca3af;");
            }
        });

        // Keyboard shortcuts
        rootPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                switch (e.getCode()) {
                    case SPACE -> { playPause();  e.consume(); }
                    case RIGHT -> { nextSong();   e.consume(); }
                    case LEFT  -> { prevSong();   e.consume(); }
                    case UP    -> { volumeSlider.setValue(Math.min(1.0, volumeSlider.getValue() + 0.05)); e.consume(); }
                    case DOWN  -> { volumeSlider.setValue(Math.max(0.0, volumeSlider.getValue() - 0.05)); e.consume(); }
                    default -> {}
                }
            });
        });

        // Drag & drop file
        rootPane.setOnDragOver(ev -> { if (ev.getDragboard().hasFiles()) ev.acceptTransferModes(TransferMode.COPY); ev.consume(); });
        rootPane.setOnDragDropped(ev -> {
            for (File f : ev.getDragboard().getFiles()) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".wav")) playlist.add(new Song(f));
            }
            savePlaylist(); ev.setDropCompleted(true); ev.consume();
        });

        loadPlaylist();
    }

    // ── Album Art ─────────────────────────────────────────────────────────────
    private void showAlbumArt(byte[] artBytes) {
        if (artBytes != null && artBytes.length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(artBytes));
                albumArtView.setImage(img);
                Rectangle clip = new Rectangle(152, 152);
                clip.setArcWidth(18);
                clip.setArcHeight(18);
                albumArtView.setClip(clip);
                albumArtView.setVisible(true);
                musicNoteLabel.setVisible(false);
                albumArtPane.setStyle("-fx-background-color: transparent; -fx-background-radius: 14;");
                return;
            } catch (Exception ignored) {}
        }
        resetAlbumArt();
    }

    private void resetAlbumArt() {
        albumArtView.setVisible(false);
        albumArtView.setImage(null);
        musicNoteLabel.setVisible(true);
        albumArtPane.setStyle(ALBUM_ART_STYLE);
    }

    // ── Metadata (artist, art, lirik dari ID3) ────────────────────────────────
    private void extractMetadata(File file, Song song) {
        try {
            Mp3File mp3 = new Mp3File(file);
            String artist = null;
            byte[] art    = null;
            String embeddedLyrics = null;

            if (mp3.hasId3v2Tag()) {
                ID3v2 tag = mp3.getId3v2Tag();
                artist = tag.getArtist();
                art    = tag.getAlbumImage();
                embeddedLyrics = tag.getLyrics();
            } else if (mp3.hasId3v1Tag()) {
                ID3v1 tag = mp3.getId3v1Tag();
                artist = tag.getArtist();
            }

            if (artist != null && !artist.trim().isEmpty()) song.setArtist(artist.trim());

            final String finalArtist = song.getArtist();
            final byte[] finalArt    = art;
            final String finalLyrics = embeddedLyrics;

            Platform.runLater(() -> {
                artistLabel.setText(finalArtist != null ? finalArtist : "");
                showAlbumArt(finalArt);
                songList.refresh(); // refresh agar artis tampil di playlist
            });

            if (finalLyrics != null && !finalLyrics.trim().isEmpty()) {
                Platform.runLater(() -> lyricsLabel.setText(finalLyrics.trim()));
            } else {
                fetchLyrics(finalArtist, song.getTitle());
            }
        } catch (Exception e) {
            Platform.runLater(() -> { artistLabel.setText(""); resetAlbumArt(); });
            fetchLyrics(null, song.getTitle());
        }
    }

    // ── Lirik dari API lyrics.ovh ─────────────────────────────────────────────
    private void fetchLyrics(String artist, String title) {
        if (artist == null || artist.isEmpty()) {
            Platform.runLater(() -> lyricsLabel.setText("Artis tidak diketahui — lirik tidak dapat dicari otomatis."));
            return;
        }
        Platform.runLater(() -> lyricsLabel.setText("⟳  Mencari lirik..."));

        final int gen = playGeneration;
        new Thread(() -> {
            try {
                String url = "https://api.lyrics.ovh/v1/"
                        + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(title,  StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(10_000);

                if (conn.getResponseCode() != 200) {
                    if (playGeneration == gen) Platform.runLater(() ->
                        lyricsLabel.setText("Lirik tidak ditemukan untuk lagu ini."));
                    return;
                }

                String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int start = resp.indexOf("\"lyrics\":\"");
                if (start >= 0) {
                    start += 10;
                    int end = resp.lastIndexOf("\"");
                    if (end > start) {
                        String lyrics = resp.substring(start, end)
                                .replace("\\n", "\n").replace("\\r", "")
                                .replace("\\/", "/").replace("\\\"", "\"").trim();
                        if (playGeneration == gen) Platform.runLater(() -> lyricsLabel.setText(lyrics));
                        return;
                    }
                }
                if (playGeneration == gen) Platform.runLater(() ->
                    lyricsLabel.setText("Lirik tidak ditemukan."));
            } catch (Exception e) {
                if (playGeneration == gen) Platform.runLater(() ->
                    lyricsLabel.setText("Gagal mengambil lirik. Cek koneksi internet."));
            }
        }).start();
    }

    // ── Tambah dari Google Drive / Dropbox / URL ──────────────────────────────
    @FXML
    public void addFromDrive() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tambah Lagu dari Cloud");
        dialog.setHeaderText("Dukung: Google Drive · Dropbox · URL langsung MP3");

        TextField linkField = new TextField();
        linkField.setPromptText("https://drive.google.com/file/d/...");
        linkField.setPrefWidth(400);

        TextField nameField = new TextField();
        nameField.setPromptText("Contoh: Coldplay - Yellow");
        nameField.setPrefWidth(400);

        VBox content = new VBox(8, new Label("Link file MP3:"), linkField, new Label("Nama lagu:"), nameField);
        content.setPadding(new Insets(10, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Platform.runLater(linkField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        String link = linkField.getText().trim();
        String name = nameField.getText().trim();
        if (link.isEmpty()) return;

        String url = convertCloudLink(link);
        if (url == null) {
            new Alert(Alert.AlertType.ERROR, "Link tidak dikenali.\nGunakan link Google Drive, Dropbox, atau URL langsung ke file .mp3").showAndWait();
            return;
        }
        if (name.isEmpty()) name = "☁ Lagu " + (playlist.size() + 1);
        playlist.add(new Song(url, name));
        savePlaylist();
        tabPane.getSelectionModel().select(0);
    }

    private String convertCloudLink(String input) {
        Matcher m1 = Pattern.compile("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)").matcher(input);
        if (m1.find()) return "https://drive.google.com/uc?export=download&id=" + m1.group(1);
        Matcher m2 = Pattern.compile("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)").matcher(input);
        if (m2.find()) return "https://drive.google.com/uc?export=download&id=" + m2.group(1);
        if (input.contains("drive.google.com/uc")) return input;
        if (input.contains("dropbox.com")) return input.replaceAll("[?&]dl=0", "") + (input.contains("?") ? "&dl=1" : "?dl=1");
        if ((input.startsWith("http://") || input.startsWith("https://")) && (input.contains(".mp3") || input.contains(".wav"))) return input;
        return null;
    }

    private byte[] downloadFromCloud(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000); conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("Server cloud mengembalikan HTTP " + code + ".");
        String ct = conn.getContentType();
        if (ct != null && ct.startsWith("text/html"))
            throw new IOException("Server mengembalikan halaman HTML, bukan file audio.\nPastikan file di-share 'Anyone with link' dan ukuran ≤ 100 MB.");
        try (InputStream is = conn.getInputStream()) { return is.readAllBytes(); }
    }

    // ── Sort A-Z ──────────────────────────────────────────────────────────────
    @FXML public void sortAZ() {
        FXCollections.sort(playlist, Comparator.comparing(s -> s.getTitle().toLowerCase()));
        savePlaylist();
    }

    // ── Clear Queue ───────────────────────────────────────────────────────────
    @FXML public void clearQueue() { queue.clear(); }

    // ── Mini Player ───────────────────────────────────────────────────────────
    @FXML
    public void toggleMiniPlayer() {
        Stage mainStage = (Stage) rootPane.getScene().getWindow();
        if (miniStage != null && miniStage.isShowing()) { miniStage.close(); mainStage.show(); return; }

        Label noteLabel = new Label("♪");
        noteLabel.setStyle("-fx-text-fill: #a78bfa; -fx-font-size: 22; -fx-padding: 0 8 0 0;");
        miniSongLbl = new Label((isPlaying || isPaused) ? songTitle.getText() : "— Tidak ada lagu —");
        miniSongLbl.setStyle("-fx-text-fill: #e5e7eb; -fx-font-size: 12; -fx-font-weight: bold;");
        miniSongLbl.setMaxWidth(190);

        miniPlayBtn = new Button(isPlaying ? "❚❚" : "▶");
        miniPlayBtn.setStyle("-fx-background-color: #7c3aed; -fx-text-fill: white; -fx-background-radius: 50%; -fx-min-width: 36; -fx-min-height: 36; -fx-cursor: hand; -fx-font-size: 14; -fx-border-color: transparent;");
        miniPlayBtn.setOnAction(e -> playPause());

        Button prevBtn = new Button("|◀");
        prevBtn.setOnAction(e -> prevSong());
        prevBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: #d1d5db; -fx-background-radius: 50%; -fx-min-width: 30; -fx-min-height: 30; -fx-cursor: hand; -fx-border-color: transparent; -fx-font-size: 11;");

        Button nextBtn = new Button("▶|");
        nextBtn.setOnAction(e -> nextSong());
        nextBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: #d1d5db; -fx-background-radius: 50%; -fx-min-width: 30; -fx-min-height: 30; -fx-cursor: hand; -fx-border-color: transparent; -fx-font-size: 11;");

        Button fullBtn = new Button("⊞");
        fullBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #6b7280; -fx-font-size: 16; -fx-cursor: hand; -fx-border-color: transparent;");
        fullBtn.setTooltip(new Tooltip("Kembali ke full player"));
        fullBtn.setOnAction(e -> { miniStage.close(); mainStage.show(); });

        miniProgress = new ProgressBar(totalDurationSec > 0 ? (double) elapsedSeconds / totalDurationSec : 0);
        miniProgress.setMaxWidth(Double.MAX_VALUE); miniProgress.setPrefHeight(3);
        miniProgress.setStyle("-fx-accent: #7c3aed; -fx-background-color: #2d2d4e; -fx-background-radius: 2;");

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6, prevBtn, miniPlayBtn, nextBtn); controls.setAlignment(Pos.CENTER);
        HBox topRow = new HBox(8, noteLabel, miniSongLbl, spacer, controls, fullBtn); topRow.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(8, topRow, miniProgress);
        root.setStyle("-fx-background-color: linear-gradient(from 0% 0% to 100% 100%, #1a1a35, #0d0d1f); -fx-padding: 12 16 10 16;");

        final double[] delta = {0, 0};
        root.setOnMousePressed(e -> { delta[0] = miniStage.getX() - e.getScreenX(); delta[1] = miniStage.getY() - e.getScreenY(); });
        root.setOnMouseDragged(e -> { miniStage.setX(e.getScreenX() + delta[0]); miniStage.setY(e.getScreenY() + delta[1]); });

        miniStage = new Stage();
        miniStage.setTitle("Mini Player"); miniStage.initStyle(StageStyle.UNDECORATED);
        miniStage.setScene(new Scene(root, 420, 80)); miniStage.setAlwaysOnTop(true); miniStage.setResizable(false);
        miniStage.setOnHidden(e -> { miniSongLbl = null; miniPlayBtn = null; miniProgress = null; if (!mainStage.isShowing()) mainStage.show(); });

        javafx.geometry.Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        miniStage.setX(screen.getMaxX() - 440); miniStage.setY(screen.getMaxY() - 110);
        mainStage.hide(); miniStage.show();
    }

    private void updateMiniPlayer() {
        if (miniStage == null || !miniStage.isShowing()) return;
        if (miniSongLbl  != null) miniSongLbl.setText((isPlaying || isPaused) ? songTitle.getText() : "— Tidak ada lagu —");
        if (miniPlayBtn  != null) miniPlayBtn.setText(isPlaying ? "❚❚" : "▶");
        if (miniProgress != null && totalDurationSec > 0) miniProgress.setProgress((double) elapsedSeconds / totalDurationSec);
    }

    // ── Tambah / Hapus ────────────────────────────────────────────────────────
    @FXML public void addSong() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Pilih File Musik");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("File Musik", "*.mp3", "*.wav"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files != null) { for (File f : files) playlist.add(new Song(f)); savePlaylist(); }
    }

    @FXML public void removeSong() {
        Song s = songList.getSelectionModel().getSelectedItem();
        if (s != null) { playlist.remove(s); savePlaylist(); }
    }

    // ── Kontrol playback ──────────────────────────────────────────────────────
    @FXML public void playPause() {
        if (!isPlaying && !isPaused) { playSelected(); }
        else if (isPaused)  { isPaused = false; isPlaying = true;  playPauseBtn.setText("❚❚"); startTimer(); }
        else                { isPaused = true;  isPlaying = false; playPauseBtn.setText("▶");  stopTimer();  }
        updateMiniPlayer();
    }

    @FXML public void stopSong() {
        stopRequested = true; isPaused = false; elapsedSeconds = 0;
        hentikanLine();
        if (playerThread != null) playerThread.interrupt();
        stopTimer(); isPlaying = false; currentlyPlayingSong = null;
        songList.refresh(); playPauseBtn.setText("▶");
        progressSlider.setValue(0); currentTime.setText("0:00"); totalTime.setText("0:00");
        artistLabel.setText(""); resetAlbumArt();
        lyricsLabel.setText("Putar lagu untuk melihat lirik");
        updateMiniPlayer();
    }

    @FXML public void nextSong() {
        elapsedSeconds = 0;
        if (!queue.isEmpty()) {
            Song next = queue.remove(0); stopSong();
            int idx = filteredPlaylist.indexOf(next);
            if (idx >= 0) songList.getSelectionModel().select(idx);
            else { playlist.add(next); songList.getSelectionModel().select(filteredPlaylist.size() - 1); }
            playSelected(); return;
        }
        int idx = songList.getSelectionModel().getSelectedIndex();
        int total = songList.getItems().size();
        if (idx < total - 1) { stopSong(); songList.getSelectionModel().select(idx + 1); playSelected(); }
        else if (repeatMode == RepeatMode.ALL && total > 0) { stopSong(); songList.getSelectionModel().select(0); playSelected(); }
    }

    @FXML public void prevSong() {
        int idx = songList.getSelectionModel().getSelectedIndex();
        if (idx > 0) { elapsedSeconds = 0; stopSong(); songList.getSelectionModel().select(idx - 1); playSelected(); }
    }

    // ── Seek ──────────────────────────────────────────────────────────────────
    @FXML public void onSliderPressed() { userDragging = true; stopTimer(); }

    @FXML public void onSliderReleased() {
        userDragging = false;
        if (currentFileBytes == null || totalDurationSec <= 0 || !isPlaying && !isPaused) return;
        int t = (int) progressSlider.getValue(); elapsedSeconds = t; currentTime.setText(formatWaktu(t)); seekTo(t);
    }

    private void seekTo(int targetSec) {
        if (currentFileBytes == null) return;
        playGeneration++; stopRequested = true; hentikanLine();
        if (playerThread != null) playerThread.interrupt();
        final int myGen = playGeneration;
        stopRequested = false; isPaused = false; isPlaying = true;
        playPauseBtn.setText("❚❚"); startTimer();
        Song selected = songList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        final byte[] bytes = currentFileBytes;

        playerThread = new Thread(() -> {
            try {
                MpegAudioFileReader reader = new MpegAudioFileReader();
                AudioInputStream mp3In = reader.getAudioInputStream(new ByteArrayInputStream(bytes));
                AudioFormat base = mp3In.getFormat();
                AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
                AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcm, mp3In);

                int frameSize = pcm.getChannels() * 2;
                long pcmBPS = (long)(pcm.getSampleRate() * frameSize);
                long skip = (targetSec * pcmBPS / frameSize) * frameSize;
                byte[] sb = new byte[8192]; long skipped = 0;
                while (skipped < skip && playGeneration == myGen) {
                    int r = pcmIn.read(sb, 0, (int) Math.min(sb.length, skip - skipped));
                    if (r < 0) break; skipped += r;
                }
                if (playGeneration != myGen) return;

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm);
                audioLine = (SourceDataLine) AudioSystem.getLine(info);
                audioLine.open(pcm); setVolume((float) volumeSlider.getValue()); audioLine.start();

                byte[] buf = new byte[4096]; int read;
                while ((read = pcmIn.read(buf, 0, buf.length)) != -1 && playGeneration == myGen) {
                    while (isPaused && playGeneration == myGen) Thread.sleep(50);
                    if (playGeneration == myGen && audioLine != null) audioLine.write(buf, 0, read);
                }
                if (audioLine != null) audioLine.drain(); hentikanLine(); pcmIn.close();
                if (playGeneration == myGen) Platform.runLater(() -> {
                    stopTimer(); isPlaying = false; elapsedSeconds = 0;
                    playPauseBtn.setText("▶"); progressSlider.setValue(0); currentTime.setText("0:00");
                    if (repeatMode == RepeatMode.ONE) playSelected(); else nextSong();
                });
            } catch (Exception e) { if (playGeneration == myGen) System.err.println("Seek: " + e.getMessage()); }
        });
        playerThread.setDaemon(true); playerThread.start();
    }

    // ── Shuffle / Repeat ──────────────────────────────────────────────────────
    @FXML public void toggleShuffle() {
        shuffleMode = !shuffleMode;
        if (shuffleMode) {
            originalOrder = new ArrayList<>(playlist);
            List<Song> s = new ArrayList<>(playlist); Collections.shuffle(s); playlist.setAll(s);
            shuffleBtn.getStyleClass().setAll(CSS_ON);
        } else {
            playlist.setAll(originalOrder);
            shuffleBtn.getStyleClass().setAll(CSS_OFF);
        }
    }

    @FXML public void toggleRepeat() {
        switch (repeatMode) {
            case NONE -> { repeatMode = RepeatMode.ONE;  repeatBtn.setText("①"); repeatBtn.getStyleClass().setAll(CSS_ON);  }
            case ONE  -> { repeatMode = RepeatMode.ALL;  repeatBtn.setText("↺"); repeatBtn.getStyleClass().setAll(CSS_ON);  }
            case ALL  -> { repeatMode = RepeatMode.NONE; repeatBtn.setText("↺"); repeatBtn.getStyleClass().setAll(CSS_OFF); }
        }
    }

    // ── Putar lagu ────────────────────────────────────────────────────────────
    private void playSelected() {
        Song selected = songList.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        stopRequested = true; hentikanLine();
        if (playerThread != null) playerThread.interrupt();

        playGeneration++;
        final int myGen = playGeneration;
        stopRequested = false; isPaused = false; isPlaying = true;
        currentlyPlayingSong = selected;
        songList.refresh(); songList.scrollTo(selected);
        songTitle.setText(selected.isCloud() ? "⬇ Mengunduh..." : selected.getTitle());
        artistLabel.setText(selected.getArtist() != null ? selected.getArtist() : "");
        playPauseBtn.setText("❚❚"); startTimer();
        lyricsLabel.setText("⟳  Memuat...");

        recentPlayed.remove(selected); recentPlayed.add(0, selected);
        if (recentPlayed.size() > 20) recentPlayed.remove(recentPlayed.size() - 1);
        updateMiniPlayer();

        playerThread = new Thread(() -> {
            try {
                byte[] fileBytes;
                File durationFile;

                if (selected.isCloud()) {
                    fileBytes    = downloadFromCloud(selected.getFilePath());
                    durationFile = File.createTempFile("mp_dur_", ".mp3");
                    durationFile.deleteOnExit();
                    Files.write(durationFile.toPath(), fileBytes);
                    if (playGeneration == myGen) Platform.runLater(() -> songTitle.setText(selected.getTitle()));
                } else {
                    durationFile = new File(new URI(selected.getFilePath()));
                    fileBytes    = Files.readAllBytes(durationFile.toPath());
                }

                String fmt = detectFormat(fileBytes);
                if (!fmt.startsWith("MP3") && !fmt.equals("WAV"))
                    throw new Exception("Bukan file MP3!\nFormat: " + fmt);

                currentFileBytes = fileBytes;

                // Baca metadata (artist, album art, lirik embedded)
                extractMetadata(durationFile, selected);

                // Durasi
                MpegAudioFileReader reader = new MpegAudioFileReader();
                AudioFileFormat aff = reader.getAudioFileFormat(durationFile);
                Object durObj = aff.properties().get("duration");
                if (durObj instanceof Number n) totalDurationSec = (int)(n.longValue() / 1_000_000L);
                else {
                    int frames = aff.getFrameLength(); float sr = aff.getFormat().getSampleRate();
                    totalDurationSec = (frames > 0 && sr > 0) ? (int)(frames * 1152.0f / sr) : 0;
                }

                if (selected.isCloud()) durationFile.delete();

                final int dur = totalDurationSec;
                Platform.runLater(() -> {
                    totalTime.setText("-" + formatWaktu(dur));
                    progressSlider.setMax(dur > 0 ? dur : 100);
                    progressSlider.setValue(elapsedSeconds);
                });

                AudioInputStream mp3In = reader.getAudioInputStream(new ByteArrayInputStream(fileBytes));
                AudioFormat base = mp3In.getFormat();
                AudioFormat pcm  = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
                AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcm, mp3In);

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm);
                audioLine = (SourceDataLine) AudioSystem.getLine(info);
                audioLine.open(pcm); setVolume((float) volumeSlider.getValue()); audioLine.start();

                byte[] buf = new byte[4096]; int read;
                while ((read = pcmIn.read(buf, 0, buf.length)) != -1 && playGeneration == myGen) {
                    while (isPaused && playGeneration == myGen) Thread.sleep(50);
                    if (playGeneration == myGen && audioLine != null) audioLine.write(buf, 0, read);
                }
                if (audioLine != null) audioLine.drain(); hentikanLine(); pcmIn.close();

                if (playGeneration == myGen) Platform.runLater(() -> {
                    stopTimer(); isPlaying = false; elapsedSeconds = 0;
                    playPauseBtn.setText("▶"); progressSlider.setValue(0); currentTime.setText("0:00");
                    if (repeatMode == RepeatMode.ONE) playSelected(); else nextSong();
                });
            } catch (Exception e) {
                if (playGeneration == myGen) {
                    System.err.println("Error: " + e.getMessage());
                    Platform.runLater(() -> { songTitle.setText(selected.getTitle()); tampilkanError(selected, e); });
                }
            }
        });
        playerThread.setDaemon(true); playerThread.start();
    }

    // ── Volume ────────────────────────────────────────────────────────────────
    private void setVolume(float v) {
        if (audioLine != null && audioLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl g = (FloatControl) audioLine.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (v > 0) ? (float)(20.0 * Math.log10(v)) : g.getMinimum();
            g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), dB)));
        }
    }

    private void hentikanLine() {
        if (audioLine != null) { audioLine.stop(); audioLine.flush(); audioLine.close(); audioLine = null; }
    }

    private void tampilkanError(Song song, Exception e) {
        stopTimer(); isPlaying = false; playPauseBtn.setText("▶");
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error"); a.setHeaderText("Gagal memutar: " + song.getTitle());
        a.setContentText(e.getMessage()); a.showAndWait();
    }

    // ── Timer ─────────────────────────────────────────────────────────────────
    private void startTimer() {
        if (countTimer != null) countTimer.stop();
        countTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            elapsedSeconds++;
            currentTime.setText(formatWaktu(elapsedSeconds));
            if (!userDragging && totalDurationSec > 0) {
                progressSlider.setValue(elapsedSeconds);
                totalTime.setText("-" + formatWaktu(Math.max(0, totalDurationSec - elapsedSeconds)));
            }
            updateMiniPlayer();
        }));
        countTimer.setCycleCount(Timeline.INDEFINITE); countTimer.play();
    }

    private void stopTimer() { if (countTimer != null) countTimer.stop(); }
    private String formatWaktu(int s) { return String.format("%d:%02d", s / 60, s % 60); }

    // ── Simpan & Load playlist ────────────────────────────────────────────────
    public void savePlaylist() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(PLAYLIST_FILE))) {
            for (Song s : playlist) {
                if (s.isCloud()) pw.println("cloud::" + s.getFilePath() + "::" + s.getTitle());
                else             pw.println(s.getFilePath());
            }
        } catch (IOException e) { System.err.println("Gagal simpan: " + e.getMessage()); }
    }

    private void loadPlaylist() {
        File f = new File(PLAYLIST_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim(); if (line.isEmpty()) continue;
                if (line.startsWith("cloud::")) {
                    String[] parts = line.substring(7).split("::", 2);
                    if (parts.length == 2) playlist.add(new Song(parts[0], parts[1]));
                } else {
                    File sf = new File(new URI(line));
                    if (sf.exists()) playlist.add(new Song(sf));
                }
            }
        } catch (Exception e) { System.err.println("Gagal load: " + e.getMessage()); }
    }

    // ── Deteksi format ────────────────────────────────────────────────────────
    private String detectFormat(byte[] b) {
        if (b.length < 4) return "File terlalu kecil";
        if (b[0]=='I' && b[1]=='D' && b[2]=='3')                return "MP3 (ID3v2." + (b[3]&0xFF) + ")";
        if ((b[0]&0xFF)==0xFF && (b[1]&0xE0)==0xE0)             return "MP3 (raw frames)";
        if (b.length>=8 && b[4]=='f' && b[5]=='t' && b[6]=='y' && b[7]=='p') return "M4A/AAC (bukan MP3!)";
        if (b[0]=='O' && b[1]=='g' && b[2]=='g')                return "OGG Vorbis (bukan MP3!)";
        if (b[0]=='f' && b[1]=='L' && b[2]=='a' && b[3]=='C')  return "FLAC (bukan MP3!)";
        if (b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F')  return "WAV";
        return String.format("Unknown (0x%02X 0x%02X 0x%02X 0x%02X)", b[0], b[1], b[2], b[3]);
    }
}
