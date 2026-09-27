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

import com.github._1c_syntax.bsl.languageserver.infrastructure.WorkspaceScope;
import com.github._1c_syntax.bsl.languageserver.types.model.BilingualString;
import com.github._1c_syntax.bsl.languageserver.types.model.MemberDescriptor;
import com.github._1c_syntax.bsl.parser.SDBLParser;
import lombok.RequiredArgsConstructor;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Проверка обращений запроса к таблицам и полям по составу конфигурации.
 * <p>
 * Ложное замечание хуже пропущенного: по нему правят исправный код. Поэтому замечанием
 * становится только то, что разрешается однозначно, — источник, известный по имени
 * объекта метаданных, и поле, которого нет в его составе. Всё остальное молчит: поля
 * временных таблиц, вложенных запросов и внешних источников, неизвестные псевдонимы,
 * обращения без псевдонима, порядок и итоги, таблицы, о полях которых резолвер не знает.
 * <p>
 * Псевдоним ищется от запроса, в котором стоит обращение, к объемлющим. Источник без
 * таблицы (подзапрос, временная таблица, параметр) псевдоним занимает, но полей у него
 * нет — поэтому он же не даёт обращению «провалиться» к одноимённому псевдониму
 * объемлющего запроса. Проверяется только первое звено за псевдонимом: остальные звенья
 * пути ({@code Т.Контрагент.ИНН}) зависят от типов реквизитов.
 */
@Component
@WorkspaceScope
@RequiredArgsConstructor
public class QueryFieldValidator {

  /**
   * Вид найденной проблемы.
   */
  public enum ProblemKind {
    /** У таблицы нет такого поля. */
    MISSING_FIELD,
    /** Третья часть имени таблицы — не табличная часть и не виртуальная таблица. */
    UNKNOWN_VIRTUAL_TABLE
  }

  /**
   * Найденная проблема.
   *
   * @param kind       вид проблемы.
   * @param token      токен, на который указывает замечание: поле либо третья часть имени таблицы.
   * @param name       написание отсутствующего имени, как оно стоит в запросе.
   * @param tableName  имя таблицы, к которой относится проблема.
   * @param candidates поля таблицы, среди которых ищется похожее имя; пусто, если проблема не о поле.
   */
  public record Problem(ProblemKind kind, Token token, String name, String tableName,
                        List<BilingualString> candidates) {
  }

  private final QueryTableResolver tableResolver;

  /**
   * Проверяет запросы пакета.
   *
   * @param ast разобранный пакет запросов.
   * @return найденные проблемы в порядке обхода дерева; пусто, если проверить нечего либо всё верно.
   */
  public List<Problem> validate(SDBLParser.QueryPackageContext ast) {
    var run = new Run();
    run.visit(ast);
    return List.copyOf(run.problems);
  }

  /**
   * Один проход по пакету запросов: кэш поиска таблиц живёт только в нём, чтобы конфигурация,
   * изменившаяся между проверками, не читалась из устаревшего кэша.
   */
  private final class Run {
    private final Map<String, QueryTableResolver.TableLookup> lookups = new HashMap<>();
    private final Deque<Map<String, @Nullable String>> scopes = new ArrayDeque<>();
    private final List<Problem> problems = new ArrayList<>();

    void visit(ParseTree node) {
      if (node instanceof SDBLParser.QueryContext query) {
        scopes.push(sourcesOf(query.from));
        visitChildren(node);
        scopes.pop();
        return;
      }
      if (isNotSourceScope(node)) {
        return;
      }
      if (node instanceof SDBLParser.DataSourceContext dataSource) {
        checkTable(dataSource);
      } else if (node instanceof SDBLParser.ColumnContext column) {
        checkColumn(column);
      }
      visitChildren(node);
    }

    private void visitChildren(ParseTree node) {
      for (var i = 0; i < node.getChildCount(); i++) {
        visit(node.getChild(i));
      }
    }

    /**
     * Порядок, итоги и индексирование называют колонки результата, а не источников:
     * псевдоним там означает другое.
     */
    private boolean isNotSourceScope(ParseTree node) {
      return node instanceof SDBLParser.OrderByContext
        || node instanceof SDBLParser.TotalsGroupContext
        || node instanceof SDBLParser.TotalByContext
        || node instanceof SDBLParser.IndexingSetContext
        || node instanceof SDBLParser.IndexingItemContext;
    }

    /**
     * Псевдонимы источников запроса: {@code псевдоним (lower) → имя таблицы}. У источника без
     * таблицы значение {@code null}: псевдоним занят, но полей за ним нет.
     */
    private Map<String, @Nullable String> sourcesOf(SDBLParser.@Nullable DataSourcesContext dataSources) {
      var sources = new HashMap<String, @Nullable String>();
      if (dataSources != null && dataSources.tables != null) {
        dataSources.tables.forEach(dataSource -> collect(dataSource, sources));
      }
      return sources;
    }

    private void collect(SDBLParser.DataSourceContext dataSource, Map<String, @Nullable String> sources) {
      var nested = dataSource.dataSource();
      if (nested != null) {
        collect(nested, sources);
      }
      var alias = aliasOf(dataSource);
      if (alias != null) {
        sources.put(alias.toLowerCase(Locale.ROOT), hasTable(dataSource) ? QuerySources.tableNameOf(dataSource) : null);
      }
      if (dataSource.joins != null) {
        dataSource.joins.stream()
          .map(SDBLParser.JoinPartContext::dataSource)
          .filter(Objects::nonNull)
          .forEach(join -> collect(join, sources));
      }
    }

    /**
     * Псевдоним источника; у временной таблицы без псевдонима — её имя.
     */
    private static @Nullable String aliasOf(SDBLParser.DataSourceContext dataSource) {
      if (dataSource.alias() != null) {
        return dataSource.alias().name.getText();
      }
      var table = dataSource.table();
      if (table != null && table.identifier() != null) {
        return table.identifier().getText();
      }
      return null;
    }

    /**
     * Есть ли у источника таблица метаданных, а не подзапрос, временная таблица, параметр
     * или внешний источник.
     */
    private static boolean hasTable(SDBLParser.DataSourceContext dataSource) {
      if (dataSource.externalDataSourceTable() != null || dataSource.subquery() != null
        || dataSource.parameterTable() != null) {
        return false;
      }
      if (dataSource.virtualTable() != null) {
        return dataSource.virtualTable().mdo() != null;
      }
      var table = dataSource.table();
      return table != null && table.mdo() != null;
    }

    private void checkTable(SDBLParser.DataSourceContext dataSource) {
      if (!hasTable(dataSource)) {
        return;
      }
      var thirdPart = thirdPartOf(dataSource);
      if (thirdPart == null) {
        return;
      }
      var tableName = QuerySources.tableNameOf(dataSource);
      if (lookup(tableName).status() == QueryTableResolver.LookupStatus.UNKNOWN_TABLE) {
        problems.add(new Problem(ProblemKind.UNKNOWN_VIRTUAL_TABLE, thirdPart, thirdPart.getText(),
          tableName, List.of()));
      }
    }

    private static @Nullable Token thirdPartOf(SDBLParser.DataSourceContext dataSource) {
      var virtualTable = dataSource.virtualTable();
      if (virtualTable != null) {
        return virtualTable.virtualTableName;
      }
      var table = dataSource.table();
      return table == null || table.objectTableName == null ? null : table.objectTableName.getStart();
    }

    private void checkColumn(SDBLParser.ColumnContext column) {
      if (column.mdoName == null || column.columnNames == null || column.columnNames.isEmpty()) {
        return;
      }
      var alias = column.mdoName.getText().toLowerCase(Locale.ROOT);
      for (var scope : scopes) {
        if (!scope.containsKey(alias)) {
          continue;
        }
        var tableName = scope.get(alias);
        if (tableName != null) {
          checkField(column.columnNames.get(0), tableName);
        }
        return;
      }
    }

    private void checkField(ParserRuleContext field, String tableName) {
      var lookup = lookup(tableName);
      if (lookup.status() != QueryTableResolver.LookupStatus.FIELDS) {
        return;
      }
      var name = field.getText();
      if (lookup.fields().stream().anyMatch(member -> member.matches(name))
        || lookup.tabularSections().stream().anyMatch(name::equalsIgnoreCase)) {
        return;
      }
      var candidates = lookup.fields().stream().map(MemberDescriptor::bilingualName).toList();
      problems.add(new Problem(ProblemKind.MISSING_FIELD, field.getStart(), name, tableName, candidates));
    }

    private QueryTableResolver.TableLookup lookup(String tableName) {
      return lookups.computeIfAbsent(tableName, tableResolver::lookup);
    }
  }
}
