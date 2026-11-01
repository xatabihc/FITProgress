package ru.fitprogress.ai

const val MIGRATION_1_2_SQL = "CREATE TABLE IF NOT EXISTS `personal_records` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))"
