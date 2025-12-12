package com.example.timescapedemo

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(val role: ChatRole, var content: String)

enum class ChatRole { USER, ASSISTANT }

class ChatMessageAdapter(
    private val messages: MutableList<ChatMessage>
) : RecyclerView.Adapter<ChatMessageAdapter.ChatVH>() {

    inner class ChatVH(view: View) : RecyclerView.ViewHolder(view) {
        private val messageView: TextView = view.findViewById(R.id.chatMessageText)

        fun bind(message: ChatMessage) {
            messageView.text = message.content
            val params = messageView.layoutParams as FrameLayout.LayoutParams
            if (message.role == ChatRole.USER) {
                params.gravity = Gravity.END
                messageView.setBackgroundResource(R.drawable.bg_chat_user)
                messageView.setTextColor(messageView.resources.getColor(android.R.color.white, null))
            } else {
                params.gravity = Gravity.START
                messageView.setBackgroundResource(R.drawable.bg_chat_assistant)
                messageView.setTextColor(messageView.resources.getColor(android.R.color.black, null))
            }
            messageView.layoutParams = params
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatVH(view)
    }

    override fun onBindViewHolder(holder: ChatVH, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    fun refresh() {
        notifyDataSetChanged()
    }
}
