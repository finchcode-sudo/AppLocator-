package com.example.applocator

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.applocator.data.AppLocation
import com.example.applocator.databinding.ItemAppBinding

class AppListAdapter(
    private val onClick: (AppLocation) -> Unit
) : ListAdapter<AppLocation, AppListAdapter.VH>(DIFF) {

    object DIFF : DiffUtil.ItemCallback<AppLocation>() {
        override fun areItemsTheSame(a: AppLocation, b: AppLocation) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppLocation, b: AppLocation) = a == b
    }

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        holder.binding.apply {
            tvLabel.text = app.label
            tvPackage.text = app.packageName
            tvPosition.text = app.positionText()
            root.setOnClickListener { onClick(app) }
        }
    }
}