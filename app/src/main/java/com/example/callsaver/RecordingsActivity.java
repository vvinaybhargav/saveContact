package com.example.callsaver;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Lists the best-effort call recordings and plays them back.
 */
public class RecordingsActivity extends AppCompatActivity {

    private LinearLayout container;
    private TextView tvEmpty;
    private MediaPlayer player;
    private ImageView playingIcon; // the play icon of the row currently playing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        container = findViewById(R.id.ll_recordings);
        tvEmpty = findViewById(R.id.tv_empty);
        loadList();
    }

    private void loadList() {
        container.removeAllViews();
        File dir = CallRecorderService.recordingsDir(this);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".m4a"));
        if (files == null || files.length == 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        LayoutInflater inflater = getLayoutInflater();
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault());
        for (File f : files) {
            View row = inflater.inflate(R.layout.item_recording, container, false);
            TextView name = row.findViewById(R.id.tv_rec_name);
            TextView meta = row.findViewById(R.id.tv_rec_meta);
            ImageView playIcon = row.findViewById(R.id.iv_play);
            View btnPlay = row.findViewById(R.id.btn_play);
            View btnDelete = row.findViewById(R.id.btn_delete_rec);

            name.setText(f.getName().replace(".m4a", ""));
            meta.setText(sdf.format(new Date(f.lastModified())) + "  ·  " + (f.length() / 1024) + " KB");

            btnPlay.setOnClickListener(v -> togglePlay(f, playIcon));
            btnDelete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete recording?")
                    .setMessage(f.getName())
                    .setPositiveButton("Delete", (d, w) -> {
                        stopPlayback();
                        if (f.delete()) {
                            loadList();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show());

            container.addView(row);
        }
    }

    private void togglePlay(File file, ImageView icon) {
        if (player != null && player.isPlaying() && icon == playingIcon) {
            stopPlayback();
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            playingIcon = icon;
            icon.setImageResource(android.R.drawable.ic_media_pause);
        } catch (Exception e) {
            Toast.makeText(this, "Can't play this recording", Toast.LENGTH_SHORT).show();
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (playingIcon != null) {
            playingIcon.setImageResource(android.R.drawable.ic_media_play);
            playingIcon = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
    }
}
