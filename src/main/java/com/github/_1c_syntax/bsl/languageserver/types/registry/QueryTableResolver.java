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
package com.github._1c_syntax.bsl.languageserver.types.registry;

import com.github._1c_syntax.bsl.languageserver.context.ServerContextProvider;
import com.github._1c_syntax.bsl.languageserver.infrastructure.WorkspaceContextHolder;
import com.github._1c_syntax.bsl.languageserver.infrastructure.WorkspaceScope;
import com.github._1c_syntax.bsl.languageserver.types.model.MemberDescriptor;
import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.TabularSectionOwner;
import com.github._1c_syntax.bsl.mdo.storage.form.FormDynamicListAttribute;
import com.github._1c_syntax.bsl.types.MDOType;
import com.github._1c_syntax.bsl.types.MdoReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Поля таблицы языка запросов по её имени.
 * <p>
 * Откуда поля берутся, резолвер не знает: он находит объект метаданных и
 * платформенное описание таблицы, а дальше опрашивает {@link QueryTableFieldSource}
 * по очереди. При совпадении имён выигрывает источник, спрошенный раньше;
 * бестиповое поле при этом дополняется типом из следующего источника — состав
 * полей списка называет поле, но тип у записи состава есть редко.
 */
@Component
@WorkspaceScope
@RequiredArgsConstructor
public class QueryTableResolver {

  /** Сегментов в одной паре «вид.имя» ссылки объекта метаданных. */
  private static final int PAIR_SEGMENTS = 2;

  private final List<QueryTableFieldSource> sources;
  private final PlatformQueryTables platformTables;
  private final ServerContextProvider serverContextProvider;

  /**
   * Поля таблицы.
   *
   * @param tableName имя таблицы, как его задаёт конфигурация
   *                  ({@code Catalog.Номенклатура}).
   * @return поля таблицы; пусто, если ни один источник её не знает.
   */
  public List<MemberDescriptor> fields(String tableName) {
    return resolve(tableName, null);
  }

  /**
   * Поля таблицы, которую читает динамический список: к полям таблицы
   * добавляется объявленный самим списком состав полей.
   *
   * @param tableName имя основной таблицы списка; пустое, если её нет.
   * @param list      динамический список.
   * @return поля таблицы; пусто, если ни один источник её не знает.
   */
  public List<MemberDescriptor> fields(String tableName, FormDynamicListAttribute list) {
    return resolve(tableName, list);
  }

  /**
   * Что известно о таблице запроса и о её полях.
   *
   * @param status          исход поиска.
   * @param fields          поля таблицы; непусто только при {@link LookupStatus#FIELDS}.
   * @param tabularSections имена табличных частей объекта таблицы; непусто только
   *                        при {@link LookupStatus#FIELDS}. В запросе табличная часть
   *                        полем не бывает, но и ошибкой её имя не считается.
   */
  public record TableLookup(LookupStatus status, List<MemberDescriptor> fields, List<String> tabularSections) {

    private static final TableLookup UNRESOLVED = new TableLookup(LookupStatus.UNRESOLVED, List.of(), List.of());
    private static final TableLookup UNKNOWN_OBJECT = new TableLookup(LookupStatus.UNKNOWN_OBJECT, List.of(), List.of());
    private static final TableLookup UNKNOWN_TABLE = new TableLookup(LookupStatus.UNKNOWN_TABLE, List.of(), List.of());
  }

  /**
   * Исход поиска таблицы запроса.
   */
  public enum LookupStatus {
    /** Поля таблицы известны. */
    FIELDS,
    /** Объекта метаданных с таким именем в конфигурации нет. */
    UNKNOWN_OBJECT,
    /**
     * Объект есть, но третья часть имени — не табличная часть объекта и не виртуальная
     * таблица, которую знает платформа.
     */
    UNKNOWN_TABLE,
    /**
     * О таблице нечего сказать: конфигурация не прочитана либо пуста, либо у таблицы,
     * которая существует, полей резолвер не знает (например, у табличной части).
     */
    UNRESOLVED
  }

  /**
   * Ищет таблицу запроса в конфигурации и отвечает, можно ли по ней судить о полях.
   * <p>
   * Объект, которого в конфигурации нет, полей не имеет, хотя платформенные псевдополя
   * ({@code Ссылка}, {@code Представление}) у таблицы с таким именем названы по шаблону, —
   * поэтому его отличают от таблицы, у которой поля просто неизвестны.
   *
   * @param tableName имя таблицы, как его задаёт запрос ({@code Справочник.Номенклатура},
   *                  {@code РегистрНакопления.Продажи.Остатки}).
   * @return исход поиска и, если поля известны, сами поля.
   */
  public TableLookup lookup(String tableName) {
    var configuration = currentConfiguration();
    if (configuration == null) {
      return TableLookup.UNRESOLVED;
    }
    var mdo = findMdo(tableName, configuration);
    if (mdo == null) {
      return isNamedByReference(tableName) ? TableLookup.UNKNOWN_OBJECT : TableLookup.UNRESOLVED;
    }
    var fields = resolve(tableName, null);
    var tail = tailSegment(tableName);
    var tabularSections = tabularSectionNames(mdo);
    if (!fields.isEmpty()) {
      return new TableLookup(LookupStatus.FIELDS, fields, tabularSections);
    }
    if (tail == null || isTabularSection(tabularSections, tail)) {
      return TableLookup.UNRESOLVED;
    }
    return platformTables.find(tableName) == null ? TableLookup.UNKNOWN_TABLE : TableLookup.UNRESOLVED;
  }

  /**
   * Названа ли таблица ссылкой на объект метаданных целиком — парами «вид.имя».
   * Отличает имя, которое не нашлось в конфигурации, от имени, которое разобрать нечем.
   */
  private static boolean isNamedByReference(String tableName) {
    var segments = tableName.split("\\.", -1);
    return segments.length >= PAIR_SEGMENTS && MDOType.fromValue(segments[0]).isPresent();
  }

  /**
   * Сегмент имени за ссылкой объекта: табличная часть либо виртуальная таблица.
   *
   * @return сегмент; {@code null}, если имя — ссылка на объект и ничего больше.
   */
  private static @Nullable String tailSegment(String tableName) {
    var segments = tableName.split("\\.", -1);
    return segments.length == PAIR_SEGMENTS + 1 ? segments[PAIR_SEGMENTS] : null;
  }

  private static List<String> tabularSectionNames(MD mdo) {
    if (!(mdo instanceof TabularSectionOwner owner)) {
      return List.of();
    }
    return owner.getTabularSections().stream().map(MD::getName).toList();
  }

  private static boolean isTabularSection(List<String> tabularSections, String name) {
    return tabularSections.stream().anyMatch(name::equalsIgnoreCase);
  }

  private List<MemberDescriptor> resolve(String tableName, @Nullable FormDynamicListAttribute list) {
    var match = platformTables.find(tableName);
    var configuration = currentConfiguration();
    var request = new QueryTableRequest(
      tableName,
      findMdo(tableName, configuration),
      configuration,
      match == null ? null : match.table(),
      match == null ? Map.of() : match.nameBindings(),
      list);

    var byName = new LinkedHashMap<String, MemberDescriptor>();
    for (var source : sources) {
      for (var member : source.fields(request)) {
        merge(byName, member);
      }
    }
    return List.copyOf(new ArrayList<>(byName.values()));
  }

  /**
   * Кладёт поле в набор. Поле, названное раньше, остаётся — но если оно без
   * типа, тип берётся у одноимённого поля из следующего источника.
   */
  private static void merge(Map<String, MemberDescriptor> byName, MemberDescriptor member) {
    var key = member.name().toLowerCase(Locale.ROOT);
    var existing = byName.putIfAbsent(key, member);
    if (existing != null && existing.returnTypes().isEmpty() && !member.returnTypes().isEmpty()) {
      byName.put(key, existing.withReturnTypes(member.returnTypes()));
    }
  }

  /**
   * Объект метаданных таблицы. Имя таблицы начинается его ссылкой
   * ({@code Catalog.Номенклатура}), а у подчинённого объекта ссылка продолжается
   * такими же парами «вид.имя»
   * ({@code ExternalDataSource.X.Table.Y}) — поэтому берутся все пары подряд.
   * Сегмент, который парой не является, ссылку заканчивает: так отделяется
   * имя виртуальной таблицы ({@code AccumulationRegister.Продажи.Turnovers}).
   *
   * @return объект метаданных; {@code null}, если такого имени в конфигурации нет.
   */
  private static @Nullable MD findMdo(String tableName, @Nullable CF configuration) {
    if (configuration == null) {
      return null;
    }
    var segments = tableName.split("\\.", -1);
    MdoReference reference = null;
    var consumed = 0;
    for (var i = 0; i + 1 < segments.length; i += PAIR_SEGMENTS) {
      var mdoType = MDOType.fromValue(segments[i]);
      if (mdoType.isEmpty()) {
        break;
      }
      reference = reference == null
        ? MdoReference.create(mdoType.get(), segments[i + 1])
        : MdoReference.create(reference, mdoType.get(), segments[i + 1]);
      consumed = i + PAIR_SEGMENTS;
    }
    if (reference == null || segments.length - consumed > 1) {
      // Хвост длиннее одного сегмента — имя не разобрано: одним сегментом
      // называется виртуальная таблица, а больше одного оставляет только
      // неопознанная пара, и объект тогда нашёлся бы не тот.
      return null;
    }
    return configuration.findChild(reference).orElse(null);
  }

  /**
   * Метаданные текущего workspace. {@code ServerContext} — прототипный бин, и
   * внедрённый напрямую он оказался бы пустым, поэтому контекст берётся у
   * провайдера по workspace текущего потока.
   *
   * @return метаданные; {@code null}, если workspace не выбран либо конфигурация
   *   ещё не прочитана.
   */
  private @Nullable CF currentConfiguration() {
    var workspaceUri = WorkspaceContextHolder.get();
    if (workspaceUri == null) {
      return null;
    }
    var serverContext = serverContextProvider.getAllContexts().get(workspaceUri);
    if (serverContext == null) {
      return null;
    }
    var configuration = serverContext.getConfiguration();
    return configuration.isEmpty() ? null : configuration;
  }
}
