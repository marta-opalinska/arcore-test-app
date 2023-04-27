package com.example.testactivity_arcoreissue


// percentage value of median diameter used to detect and then filter outliers
private const val DIAMETER_OUTLIERS_PERCENTAGE_VALUE = 0.15


fun sortingSelection(array: MutableList<Float>): MutableList<Float> {

    for (i in 0 until array.size - 1) {
        var min = i
        for (j in i + 1 until array.size) {
            if (array[j] < array[min]) {
                min = j
            }
        }
        var temp = array[i]
        array[i] = array[min]
        array[min] = temp
    }

    return array
}

fun median(array: MutableList<Float>): Double {
    sortingSelection(array)

    return if (array.size % 2 == 0) {
        ((array[array.size / 2] + array[array.size / 2 - 1]) / 2).toDouble()
    } else {
        (array[array.size / 2]).toDouble()
    }
}

fun calculateAverageDiameterAndDepth(
    distancesList: MutableList<Pair<Float, Float>>
): Pair<Float, Float> {
    if (distancesList.isNotEmpty()) {
        // Calculate the mean value of the list
        val diameterList = distancesList.map { it.first }
        val diameterMedian = median(diameterList as MutableList<Float>)
//        Log.d(TAG, "List: $diameterList and mean $diameterMedian")

        // Remove outliers that are more or less than 15% of the mean value
        val maxValue = diameterMedian * (1 + DIAMETER_OUTLIERS_PERCENTAGE_VALUE)
        val minValue = diameterMedian * (1 - DIAMETER_OUTLIERS_PERCENTAGE_VALUE)
        val filteredDistances = distancesList.filter { it.first in minValue..maxValue }

//        Log.d(TAG, "Filtered list: $filteredDistances")

        // Calculate the median of the remaining values for both lists - diameter and distance
        // from the phone
        return Pair(
            filteredDistances.map { it.first }.average().toFloat(),
            filteredDistances.map { it.second }.average().toFloat()
        )
    }
    return Pair(0f, 0f)
}
