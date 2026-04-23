package com.gobag.core.model

private val supported_bag_sizes = setOf(46, 66)

fun normalize_bag_size_liters(size_liters: Int): Int = when (size_liters) {
    25, 44, 46 -> 46
    66 -> 66
    else -> size_liters
}

fun is_supported_bag_size_liters(size_liters: Int): Boolean {
    return normalize_bag_size_liters(size_liters) in supported_bag_sizes
}

fun normalize_bag_template_id(template_id: String): String {
    return when (template_id.trim().lowercase()) {
        "template_25l", "template_44l", "template_46l" -> "template_46l"
        "template_66l" -> "template_66l"
        else -> template_id
    }
}

fun template_id_for_bag_size_liters(size_liters: Int): String {
    return when (normalize_bag_size_liters(size_liters)) {
        46 -> "template_46l"
        66 -> "template_66l"
        else -> "template_46l"
    }
}
