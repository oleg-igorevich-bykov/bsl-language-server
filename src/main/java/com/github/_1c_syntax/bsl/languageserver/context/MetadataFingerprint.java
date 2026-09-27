/*
 * This file is a part of BSL Language Server.
 *
 * Copyright (c) 2018-2026
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Fedkin <nixel2007@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * BSL Language Server is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * BSL Language Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BSL Language Server.
 */
package com.github._1c_syntax.bsl.languageserver.context;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Отпечаток файлов метаданных рабочей копии: меняется, когда файл метаданных
 * (XML выгрузки конфигуратора либо {@code .mdo} EDT) появился, исчез, изменил размер
 * или время записи.
 * <p>
 * Модули ({@code .bsl}, {@code .os}) в отпечаток не входят: их текст на состав
 * метаданных не влияет. Каталоги, имя которых начинается с точки ({@code .git} и подобные),
 * не обходятся. Отпечаток не зависит от порядка обхода.
 */
@Slf4j
final class MetadataFingerprint {

  private static final String XML_EXTENSION = ".xml";
  private static final String MDO_EXTENSION = ".mdo";

  private MetadataFingerprint() {
    // утилитный класс
  }

  /**
   * Считает отпечаток файлов метаданных под корнем.
   *
   * @param root корень конфигурации.
   * @return отпечаток; одинаковый у одного и того же набора файлов метаданных.
   */
  static long of(Path root) {
    var accumulator = new long[1];
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          var name = dir.getFileName();
          if (!dir.equals(root) && name != null && name.toString().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (isMetadataFile(file)) {
            accumulator[0] += mix(file, attrs);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          // Файл, который не удалось прочитать, отпечатка не меняет: так же его не прочтёт и разбор.
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      LOGGER.debug("Не удалось обойти каталог метаданных {}", root, e);
    }
    return accumulator[0];
  }

  private static boolean isMetadataFile(Path file) {
    var name = file.getFileName();
    if (name == null) {
      return false;
    }
    var lowerName = name.toString().toLowerCase(Locale.ROOT);
    return lowerName.endsWith(XML_EXTENSION) || lowerName.endsWith(MDO_EXTENSION);
  }

  /**
   * Вклад одного файла. Складываются вклады всех файлов, поэтому порядок обхода не важен,
   * а перемешивание (splitmix64) не даёт вкладам разных файлов гасить друг друга.
   */
  private static long mix(Path file, BasicFileAttributes attrs) {
    long value = file.toString().hashCode();
    value = value * 31L + attrs.lastModifiedTime().toMillis();
    value = value * 31L + attrs.size();
    value += 0x9E3779B97F4A7C15L;
    value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
    return value ^ (value >>> 31);
  }
}
