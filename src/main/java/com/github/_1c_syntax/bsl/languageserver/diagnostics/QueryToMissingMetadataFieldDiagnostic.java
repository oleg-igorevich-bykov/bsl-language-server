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
package com.github._1c_syntax.bsl.languageserver.diagnostics;

import com.github._1c_syntax.bsl.languageserver.configuration.Language;
import com.github._1c_syntax.bsl.languageserver.context.DocumentContext;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticMetadata;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticScope;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticSeverity;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticTag;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticType;
import com.github._1c_syntax.bsl.languageserver.types.model.BilingualString;
import com.github._1c_syntax.bsl.languageserver.types.registry.QueryFieldValidator;
import com.github._1c_syntax.bsl.languageserver.types.registry.QueryFieldValidator.Problem;
import com.github._1c_syntax.bsl.languageserver.utils.Trees;
import com.github._1c_syntax.bsl.types.ConfigurationSource;
import lombok.RequiredArgsConstructor;
import org.eclipse.lsp4j.Diagnostic;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Поле или виртуальная таблица запроса, которых нет у существующего объекта метаданных.
 * <p>
 * Состав таблицы берётся из конфигурации рабочей копии: реквизиты, измерения, ресурсы,
 * стандартные реквизиты, общие реквизиты и поля виртуальных таблиц регистров. Что нельзя
 * разрешить однозначно (поля временных таблиц и подзапросов, неизвестные псевдонимы,
 * внешние источники), замечанием не становится — см. {@link QueryFieldValidator}.
 * Несуществующий объект метаданных отмечает {@link QueryToMissingMetadataDiagnostic}.
 */
@DiagnosticMetadata(
  type = DiagnosticType.ERROR,
  severity = DiagnosticSeverity.MAJOR,
  scope = DiagnosticScope.BSL,
  minutesToFix = 5,
  tags = {
    DiagnosticTag.SUSPICIOUS,
    DiagnosticTag.SQL
  }
)
@RequiredArgsConstructor
public class QueryToMissingMetadataFieldDiagnostic extends AbstractSDBLVisitorDiagnostic {

  /** Сколько ближайших имён называет сообщение. */
  private static final int MAX_SUGGESTIONS = 3;

  /** Доля длины имени, на которую оно может отличаться от подсказки, — но не меньше двух букв. */
  private static final int TYPO_LENGTH_DIVISOR = 3;
  private static final int MIN_TYPO_DISTANCE = 2;

  private final QueryFieldValidator validator;

  // Свой обход, а не visitQueryPackage(): валидатору нужны не только AST, но и полный поток
  // токенов запроса (распознаёт шаблонные плейсхолдеры вроде "?" — см. QueryFieldValidator),
  // а стандартный визиторный проход AbstractSDBLVisitorDiagnostic токены не сохраняет.
  @Override
  public List<Diagnostic> getDiagnostics(DocumentContext documentContext) {
    this.documentContext = documentContext;
    diagnosticStorage.clearDiagnostics();
    if (documentContext.getServerContext().getConfiguration().getConfigurationSource()
      == ConfigurationSource.EMPTY) {
      return diagnosticStorage.getDiagnostics();
    }
    for (var tokenizer : documentContext.getQueries()) {
      var ast = tokenizer.getAst();
      if (ast != null && !Trees.treeContainsErrors(ast)) {
        validator.validate(tokenizer).forEach(this::report);
      }
    }
    return diagnosticStorage.getDiagnostics();
  }

  private void report(Problem problem) {
    switch (problem.kind()) {
      case MISSING_FIELD -> diagnosticStorage.addDiagnostic(problem.token(), missingFieldMessage(problem));
      case UNKNOWN_VIRTUAL_TABLE -> diagnosticStorage.addDiagnostic(problem.token(),
        info.getResourceString("unknownTableMessage", problem.name(), objectOf(problem.tableName())));
    }
  }

  private String missingFieldMessage(Problem problem) {
    var suggestions = closestNames(problem);
    if (suggestions.isEmpty()) {
      return info.getMessage(problem.name(), problem.tableName());
    }
    return info.getResourceString("missingFieldWithSuggestionsMessage",
      problem.name(), problem.tableName(), String.join(", ", suggestions));
  }

  /**
   * Имя объекта метаданных из имени таблицы: без третьей части ({@code РегистрНакопления.Продажи}).
   */
  private static String objectOf(String tableName) {
    var segments = tableName.split("\\.", -1);
    return segments.length <= 2 ? tableName : segments[0] + "." + segments[1];
  }

  /**
   * До трёх имён полей таблицы, ближайших к написанному. Написание берётся на том языке, на
   * котором набрано имя: русское имя подсказывается русским, английское — английским.
   */
  private static List<String> closestNames(Problem problem) {
    var language = hasCyrillic(problem.name()) ? Language.RU : Language.EN;
    var typed = problem.name().toLowerCase(Locale.ROOT);
    var threshold = Math.max(MIN_TYPO_DISTANCE, typed.length() / TYPO_LENGTH_DIVISOR);
    return problem.candidates().stream()
      .map(candidate -> candidate.forLanguage(language))
      .distinct()
      .map(candidate -> new Candidate(candidate, distance(typed, candidate.toLowerCase(Locale.ROOT))))
      .filter(candidate -> candidate.distance() <= threshold)
      .sorted(Comparator.comparingInt(Candidate::distance).thenComparing(Candidate::name))
      .limit(MAX_SUGGESTIONS)
      .map(Candidate::name)
      .collect(Collectors.toList());
  }

  private static boolean hasCyrillic(String value) {
    return value.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
  }

  private record Candidate(String name, int distance) {
  }

  /**
   * Расстояние Левенштейна между строками.
   */
  private static int distance(String left, String right) {
    var previous = new int[right.length() + 1];
    var current = new int[right.length() + 1];
    for (var j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }
    for (var i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (var j = 1; j <= right.length(); j++) {
        var substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()];
  }
}
