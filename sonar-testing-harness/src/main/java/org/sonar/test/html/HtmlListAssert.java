/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.test.html;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.jsoup.nodes.Element;

public class HtmlListAssert extends HtmlBlockAssert<HtmlListAssert> {
  private final Iterator<Element> nextBlocks;

  public HtmlListAssert(Element list, Iterator<Element> nextBlocks) {
    super(list, HtmlListAssert.class);
    this.nextBlocks = nextBlocks;
  }

  static void verifyIsList(Element element) {
    Assertions.assertThat(element.tagName())
      .describedAs(
        "next block is neither a <%s> nor a <%s> (got <%s>):" + PRINT_FRAGMENT_TEMPLATE,
        "ul", "ol", element.tagName(), element.toString())
      .isIn("ul", "ol");
  }

  /**
   * Verifies the text of every items in the current list is equal to the specified strings, in order.
   */
  public HtmlListAssert withItemTexts(String firstItemText, String... otherItemsText) {
    isNotNull();

    List<String> itemsText = actual.children()
      .stream()
      .filter(t -> t.tagName().equals("li"))
      .map(Element::text)
      .collect(Collectors.toList());

    String[] itemTexts = Stream.concat(
      Stream.of(firstItemText),
      Arrays.stream(otherItemsText))
      .toArray(String[]::new);
    Assertions.assertThat(itemsText)
      .describedAs(PRINT_FRAGMENT_TEMPLATE, actual)
      .containsOnly(itemTexts);

    return this;
  }

  public HtmlListAssert hasList() {
    isNotNull();

    Assertions.assertThat(nextBlocks.hasNext())
      .describedAs("no more block")
      .isTrue();

    Element element = nextBlocks.next();
    verifyIsList(element);

    return new HtmlListAssert(element, nextBlocks);
  }

  /**
   * Convenience method.
   * Sames as {@code hasParagraph().withText(text)}.
   */
  public HtmlParagraphAssert hasParagraph(String text) {
    return hasParagraph()
      .withText(text);
  }

  /**
   * Convenience method.
   * Sames as {@code hasParagraph().withText("")}.
   */
  public HtmlParagraphAssert hasEmptyParagraph() {
    return hasParagraph()
      .withText("");
  }

  public HtmlParagraphAssert hasParagraph() {
    isNotNull();

    Assertions.assertThat(nextBlocks.hasNext())
      .describedAs("no more block")
      .isTrue();

    Element element = nextBlocks.next();
    HtmlParagraphAssert.verifyIsParagraph(element);

    return new HtmlParagraphAssert(element, nextBlocks);
  }

  /**
   * Verifies there is no more list in the block.
   */
  public void noMoreBlock() {
    isNotNull();

    Assertions.assertThat(nextBlocks.hasNext())
      .describedAs("there are still some block. Next one:" + PRINT_FRAGMENT_TEMPLATE,
        new Object() {
          @Override
          public String toString() {
            return nextBlocks.next().toString();
          }
        })
      .isFalse();
  }

}
