package app.atlas.render.py

/** Python's `<` on floats as a comparator: values are equal unless one is strictly less (so -0.0 == 0.0). */
fun pyCompare(a: Double, b: Double): Int = if (a < b) -1 else if (b < a) 1 else 0

/** Python's tuple order on two floats each. */
fun pyCompare(a0: Double, a1: Double, b0: Double, b1: Double): Int {
    if (a0 != b0) return if (a0 < b0) -1 else if (b0 < a0) 1 else 0
    return pyCompare(a1, b1)
}

/** `lst.sort(key=key)`: stable, comparing keys with Python's `<`. */
inline fun <T> MutableList<T>.pySortBy(crossinline key: (T) -> Double) {
    sortWith { a, b -> pyCompare(key(a), key(b)) }
}

/** `sorted(lst, key=key)`. */
inline fun <T> Iterable<T>.pySortedBy(crossinline key: (T) -> Double): MutableList<T> {
    val out = toMutableList()
    out.pySortBy(key)
    return out
}
