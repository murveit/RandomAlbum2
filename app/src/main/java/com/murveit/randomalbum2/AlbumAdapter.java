package com.murveit.randomalbum2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    private final List<Album> albums;
    private final OnAlbumClickListener listener;

    // Interface for handling clicks
    public interface OnAlbumClickListener {
        void onAlbumClick(Album album);
    }

    public AlbumAdapter(List<Album> albums, OnAlbumClickListener listener) {
        this.albums = albums;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_album, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        Album album = albums.get(position);
        holder.bind(album, listener);
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    // The ViewHolder class
    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        private final ImageView albumArt;
        private final TextView albumTitle;
        private final TextView albumArtist;

        public AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.ivAlbumArtItem);
            albumTitle = itemView.findViewById(R.id.tvAlbumTitleItem);
            albumArtist = itemView.findViewById(R.id.tvAlbumArtistItem);
        }

        public void bind(final Album album, final OnAlbumClickListener listener) {
            albumTitle.setText(album.title);
            albumArtist.setText(album.artist);

            Glide.with(itemView.getContext())
                    .load(album.coverArtUri)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(albumArt);

            // Set the click listener for the entire row
            itemView.setOnClickListener(v -> listener.onAlbumClick(album));
        }
    }
}
