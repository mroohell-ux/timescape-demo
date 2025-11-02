//package com.example.timescapedemo
//
//import android.content.Context
//import android.util.AttributeSet
//import androidx.cardview.widget.CardView
//import kotlin.math.min
//
///** A CardView that always measures itself square. */
//class SquareCardView @JvmOverloads constructor(
//    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
//) : CardView(context, attrs, defStyleAttr) {
//    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
//        val w = MeasureSpec.getSize(widthMeasureSpec)
//        val h = MeasureSpec.getSize(heightMeasureSpec)
//        val side = min(w, h)
//        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
//        super.onMeasure(exact, exact)
//    }
//}
