package com.example.classbook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ClassAdapter(
    private var classList: MutableList<ClassModel>,
    private val itemClickListener: (ClassModel) -> Unit
) : RecyclerView.Adapter<ClassAdapter.ClassViewHolder>() {

    inner class ClassViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val classNameTextView: TextView = itemView.findViewById(R.id.classNameTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClassViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_class, parent, false)
        return ClassViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ClassViewHolder, position: Int) {
        val classModel = classList[position]
        holder.classNameTextView.text = classModel.name
        holder.itemView.setOnClickListener {
            itemClickListener(classModel)
        }
    }

    override fun getItemCount(): Int {
        return classList.size
    }

    fun updateClasses(newClassList: List<ClassModel>) {
        classList.clear()
        classList.addAll(newClassList)
        notifyDataSetChanged()
    }
}
