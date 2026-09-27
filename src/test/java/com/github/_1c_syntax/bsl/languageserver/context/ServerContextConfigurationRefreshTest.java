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

import com.github._1c_syntax.bsl.languageserver.util.CleanupContextBeforeClassAndAfterEachTestMethod;
import com.github._1c_syntax.bsl.mdo.AttributeOwner;
import com.github._1c_syntax.bsl.types.MDOType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Изменённые файлы метаданных рабочей копии становятся видны без перезапуска сервера:
 * конфигурация читается один раз, а перечитывается, только когда файлы метаданных
 * на диске изменились.
 */
@CleanupContextBeforeClassAndAfterEachTestMethod
class ServerContextConfigurationRefreshTest extends AbstractServerContextAwareTest {

  private static final Path FIXTURE = Path.of("src/test/resources/metadata/designer");
  private static final String CATALOG_FILE = "Catalogs/Справочник1.xml";

  @TempDir
  Path workingCopy;

  @BeforeEach
  void copyFixture() throws IOException {
    try (var files = Files.walk(FIXTURE)) {
      for (var source : files.toList()) {
        var target = workingCopy.resolve(FIXTURE.relativize(source).toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    initServerContext(workingCopy, false);
  }

  @Test
  void unchangedMetadataIsNotReread() {
    // given
    var before = context.getConfiguration();

    // when
    var refreshed = context.refreshConfigurationIfStale();

    // then
    assertThat(refreshed).isFalse();
    assertThat(context.getConfiguration()).isSameAs(before);
  }

  @Test
  void configurationNotYetReadIsLeftToItsFirstRead() {
    // given
    context.clear();

    // when
    var refreshed = context.refreshConfigurationIfStale();

    // then
    assertThat(refreshed).isFalse();
  }

  @Test
  void renamedAttributeIsSeenWithoutRestart() {
    // given
    assertThat(catalogAttributeNames()).contains("Реквизит3").doesNotContain("ТестовыйРеквизит");

    // when
    replaceInFile(CATALOG_FILE, "<Name>Реквизит3</Name>", "<Name>ТестовыйРеквизит</Name>");
    var refreshed = context.refreshConfigurationIfStale();

    // then
    assertThat(refreshed).isTrue();
    assertThat(catalogAttributeNames()).contains("ТестовыйРеквизит").doesNotContain("Реквизит3");
  }

  @Test
  void newObjectIsSeenWithoutRestart() {
    // given
    assertThat(findCatalog("НовыйСправочник")).isFalse();

    // when
    var template = readFile("Catalogs/СправочникБезГрупп.xml");
    writeFile("Catalogs/НовыйСправочник.xml", template.replace("СправочникБезГрупп", "НовыйСправочник"));
    replaceInFile("Configuration.xml",
      "<Catalog>СправочникБезГрупп</Catalog>",
      "<Catalog>СправочникБезГрупп</Catalog>\n\t\t\t<Catalog>НовыйСправочник</Catalog>");
    var refreshed = context.refreshConfigurationIfStale();

    // then
    assertThat(refreshed).isTrue();
    assertThat(findCatalog("НовыйСправочник")).isTrue();
  }

  @Test
  void changedModuleTextDoesNotRereadMetadata() {
    // given
    var before = context.getConfiguration();
    writeFile("Catalogs/Справочник1/Ext/ObjectModule.bsl", "Процедура Тест()\nКонецПроцедуры\n");

    // when
    var refreshed = context.refreshConfigurationIfStale();

    // then
    assertThat(refreshed).isFalse();
    assertThat(context.getConfiguration()).isSameAs(before);
  }

  private String catalogAttributeNames() {
    return context.getConfiguration()
      .findChild(md -> md.getMdoType() == MDOType.CATALOG && "Справочник1".equals(md.getName()))
      .map(AttributeOwner.class::cast)
      .map(owner -> owner.getAllAttributes().stream()
        .map(attribute -> attribute.getName())
        .collect(Collectors.joining(",")))
      .orElse("");
  }

  private boolean findCatalog(String name) {
    return context.getConfiguration()
      .findChild(md -> md.getMdoType() == MDOType.CATALOG && name.equals(md.getName()))
      .isPresent();
  }

  private String readFile(String relativePath) {
    try {
      return Files.readString(workingCopy.resolve(relativePath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeFile(String relativePath, String content) {
    try {
      var target = workingCopy.resolve(relativePath);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void replaceInFile(String relativePath, String from, String to) {
    var content = readFile(relativePath);
    assertThat(content).contains(from);
    writeFile(relativePath, content.replace(from, to));
  }
}
