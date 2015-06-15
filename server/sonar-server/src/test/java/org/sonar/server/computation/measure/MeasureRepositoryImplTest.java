/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.computation.measure;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import javax.annotation.CheckForNull;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.rule.RuleKey;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.measure.db.MeasureDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.core.rule.RuleDto;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.component.db.SnapshotDao;
import org.sonar.server.computation.batch.BatchReportReader;
import org.sonar.server.computation.batch.BatchReportReaderRule;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DumbComponent;
import org.sonar.server.computation.debt.Characteristic;
import org.sonar.server.computation.issue.RuleCache;
import org.sonar.server.computation.metric.Metric;
import org.sonar.server.computation.metric.MetricRepository;
import org.sonar.server.db.DbClient;
import org.sonar.server.measure.persistence.MeasureDao;
import org.sonar.server.metric.persistence.MetricDao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.guava.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class MeasureRepositoryImplTest {
  @ClassRule
  public static final DbTester dbTester = new DbTester();
  @Rule
  public BatchReportReaderRule reportReader = new BatchReportReaderRule();

  private static final String FILE_COMPONENT_KEY = "file cpt key";
  private static final DumbComponent FILE_COMPONENT = DumbComponent.builder(Component.Type.FILE, 1).setKey(FILE_COMPONENT_KEY).build();
  private static final DumbComponent OTHER_COMPONENT = DumbComponent.builder(Component.Type.FILE, 2).setKey("some other key").build();
  private static final String METRIC_KEY_1 = "metric 1";
  private static final int METRIC_ID_1 = 1;
  private static final String METRIC_KEY_2 = "metric 2";
  private static final int METRIC_ID_2 = 2;
  private final Metric metric1 = mock(Metric.class);
  private final Metric metric2 = mock(Metric.class);
  private static final long LAST_SNAPSHOT_ID = 123;
  private static final long OTHER_SNAPSHOT_ID = 369;
  private static final long COMPONENT_ID = 567;
  private static final Measure SOME_MEASURE = Measure.newMeasure().create(Measure.Level.OK);
  private static final String SOME_DATA = "some data";
  private static final RuleDto SOME_RULE = RuleDto.createFor(RuleKey.of("A", "1")).setId(963);
  private static final Characteristic SOME_CHARACTERISTIC = new Characteristic(741, "key");

  private DbClient dbClient = new DbClient(dbTester.database(), dbTester.myBatis(), new MeasureDao(), new SnapshotDao(), new MetricDao(), new ComponentDao());
  private MetricRepository metricRepository = mock(MetricRepository.class);
  private RuleCache ruleCache = mock(RuleCache.class);
  private MeasureRepositoryImpl underTest = new MeasureRepositoryImpl(dbClient, reportReader, metricRepository, ruleCache);

  private DbClient mockedDbClient = mock(DbClient.class);
  private BatchReportReader mockBatchReportReader = mock(BatchReportReader.class);
  private MeasureRepositoryImpl underTestWithMock = new MeasureRepositoryImpl(mockedDbClient, mockBatchReportReader, metricRepository, ruleCache);

  @CheckForNull
  private DbSession dbSession;

  @Before
  public void setUp() throws Exception {
    when(metric1.getKey()).thenReturn(METRIC_KEY_1);
    when(metric1.getType()).thenReturn(Metric.MetricType.STRING);
    when(metric2.getKey()).thenReturn(METRIC_KEY_2);
    when(metric2.getType()).thenReturn(Metric.MetricType.STRING);

    // references to metrics are consistent with DB by design
    when(metricRepository.getByKey(METRIC_KEY_1)).thenReturn(metric1);
    when(metricRepository.getByKey(METRIC_KEY_2)).thenReturn(metric2);
  }

  @After
  public void tearDown() throws Exception {
    if (dbSession != null) {
      dbSession.close();
    }
  }

  @Test
  public void getBaseMeasure_throws_NPE_and_does_not_open_session_if_component_is_null() {
    try {
      underTestWithMock.getBaseMeasure(null, metric1);
      fail("an NPE should have been raised");
    } catch (NullPointerException e) {
      verifyZeroInteractions(mockedDbClient);
    }
  }

  @Test
  public void getBaseMeasure_throws_NPE_and_does_not_open_session_if_metric_is_null() {
    try {
      underTestWithMock.getBaseMeasure(FILE_COMPONENT, null);
      fail("an NPE should have been raised");
    } catch (NullPointerException e) {
      verifyZeroInteractions(mockedDbClient);
    }
  }

  @Test
  public void getBaseMeasure_returns_absent_if_measure_does_not_exist_in_DB() {
    Optional<Measure> res = underTest.getBaseMeasure(FILE_COMPONENT, metric1);

    assertThat(res).isAbsent();
  }

  @Test
  public void getBaseMeasure_returns_Measure_if_measure_of_last_snapshot_only_in_DB() {
    dbTester.prepareDbUnit(getClass(), "shared.xml");
    dbSession = dbClient.openSession(false);
    dbClient.measureDao().insert(dbSession, createMeasureDto(METRIC_ID_1, LAST_SNAPSHOT_ID));
    dbClient.measureDao().insert(dbSession, createMeasureDto(METRIC_ID_2, OTHER_SNAPSHOT_ID));
    dbSession.commit();

    // metric 1 is associated to snapshot with "last=true"
    Optional<Measure> res = underTest.getBaseMeasure(FILE_COMPONENT, metric1);

    assertThat(res).isPresent();
    assertThat(res.get().getStringValue()).isEqualTo(SOME_DATA);

    // metric 2 is associated to snapshot with "last=false" => not retrieved
    res = underTest.getBaseMeasure(FILE_COMPONENT, metric2);

    assertThat(res).isAbsent();
  }

  @Test(expected = NullPointerException.class)
  public void add_throws_NPE_if_Component_argument_is_null() {
    underTest.add(null, metric1, SOME_MEASURE);
  }

  @Test(expected = NullPointerException.class)
  public void add_throws_NPE_if_Component_metric_is_null() {
    underTest.add(FILE_COMPONENT, null, SOME_MEASURE);
  }

  @Test(expected = NullPointerException.class)
  public void add_throws_NPE_if_Component_measure_is_null() {
    underTest.add(FILE_COMPONENT, metric1, null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void add_throws_UOE_if_measure_already_exists() {
    underTest.add(FILE_COMPONENT, metric1, SOME_MEASURE);
    underTest.add(FILE_COMPONENT, metric1, SOME_MEASURE);
  }

  @Test(expected = NullPointerException.class)
  public void update_throws_NPE_if_Component_argument_is_null() {
    underTest.update(null, metric1, SOME_MEASURE);
  }

  @Test(expected = NullPointerException.class)
  public void update_throws_NPE_if_Component_metric_is_null() {
    underTest.update(FILE_COMPONENT, null, SOME_MEASURE);
  }

  @Test(expected = NullPointerException.class)
  public void update_throws_NPE_if_Component_measure_is_null() {
    underTest.update(FILE_COMPONENT, metric1, null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void update_throws_UOE_if_measure_does_not_exists() {
    underTest.update(FILE_COMPONENT, metric1, SOME_MEASURE);
  }

  @Test
  public void update_supports_updating_to_the_same_value() {
    underTest.add(FILE_COMPONENT, metric1, SOME_MEASURE);
    underTest.update(FILE_COMPONENT, metric1, SOME_MEASURE);
  }

  @Test
  public void update_updates_the_stored_value() {
    Measure newMeasure = Measure.updateMeasure(SOME_MEASURE).create();

    underTest.add(FILE_COMPONENT, metric1, SOME_MEASURE);
    underTest.update(FILE_COMPONENT, metric1, newMeasure);

    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric1).get()).isSameAs(newMeasure);
  }

  @Test
  public void update_updates_the_stored_value_for_rule() {
    Measure initialMeasure = Measure.newMeasure().forRule(123).createNoValue();
    Measure newMeasure = Measure.updateMeasure(initialMeasure).create();

    underTest.add(FILE_COMPONENT, metric1, initialMeasure);
    underTest.update(FILE_COMPONENT, metric1, newMeasure);

    assertThat(underTest.getRawMeasures(FILE_COMPONENT).get(metric1.getKey()).iterator().next()).isSameAs(newMeasure);
  }

  @Test
  public void update_updates_the_stored_value_for_characteristic() {
    Measure initialMeasure = Measure.newMeasure().forCharacteristic(952).createNoValue();
    Measure newMeasure = Measure.updateMeasure(initialMeasure).create();

    underTest.add(FILE_COMPONENT, metric1, initialMeasure);
    underTest.update(FILE_COMPONENT, metric1, newMeasure);

    assertThat(underTest.getRawMeasures(FILE_COMPONENT).get(metric1.getKey()).iterator().next()).isSameAs(newMeasure);
  }

  @Test
  public void getRawMeasure_throws_NPE_without_reading_batch_report_if_component_arg_is_null() {
    try {
      underTestWithMock.getRawMeasure(null, metric1);
      fail("an NPE should have been raised");
    } catch (NullPointerException e) {
      verifyNoMoreInteractions(mockBatchReportReader);
    }
  }

  @Test
  public void getRawMeasure_throws_NPE_without_reading_batch_report_if_metric_arg_is_null() {
    try {
      underTestWithMock.getRawMeasure(FILE_COMPONENT, null);
      fail("an NPE should have been raised");
    } catch (NullPointerException e) {
      verifyNoMoreInteractions(mockBatchReportReader);
    }
  }

  @Test
  public void getRawMeasure_returns_measure_added_through_add_method() {
    underTest.add(FILE_COMPONENT, metric1, SOME_MEASURE);

    Optional<Measure> res = underTest.getRawMeasure(FILE_COMPONENT, metric1);

    assertThat(res).isPresent();
    assertThat(res.get()).isSameAs(SOME_MEASURE);

    // make sure we really match on the specified component and metric
    assertThat(underTest.getRawMeasure(OTHER_COMPONENT, metric1)).isAbsent();
    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric2)).isAbsent();
  }

  @Test
  public void getRawMeasure_returns_measure_from_batch_if_not_added_through_add_method() {
    String value = "trololo";

    reportReader.putMeasures(FILE_COMPONENT.getRef(), ImmutableList.of(
      BatchReport.Measure.newBuilder().setMetricKey(METRIC_KEY_1).setStringValue(value).build()
      ));

    Optional<Measure> res = underTest.getRawMeasure(FILE_COMPONENT, metric1);

    assertThat(res).isPresent();
    assertThat(res.get().getStringValue()).isEqualTo(value);

    // make sure we really match on the specified component and metric
    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric2)).isAbsent();
    assertThat(underTest.getRawMeasure(OTHER_COMPONENT, metric1)).isAbsent();
  }

  @Test
  public void getRawMeasure_retrieves_added_measure_over_batch_measure() {
    reportReader.putMeasures(FILE_COMPONENT.getRef(), ImmutableList.of(
      BatchReport.Measure.newBuilder().setMetricKey(METRIC_KEY_1).setStringValue("some value").build()
      ));

    Measure addedMeasure = SOME_MEASURE;
    underTest.add(FILE_COMPONENT, metric1, addedMeasure);

    Optional<Measure> res = underTest.getRawMeasure(FILE_COMPONENT, metric1);

    assertThat(res).isPresent();
    assertThat(res.get()).isSameAs(addedMeasure);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_rule_throws_NPE_if_Component_arg_is_null() {
    underTest.getRawMeasure(null, metric1, SOME_RULE);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_rule_throws_NPE_if_Metric_arg_is_null() {
    underTest.getRawMeasure(FILE_COMPONENT, null, SOME_RULE);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_rule_throws_NPE_if_Characteristic_arg_is_null() {
    underTest.getRawMeasure(FILE_COMPONENT, metric1, (RuleDto) null);
  }

  @Test
  public void getRawMeasure_for_rule_returns_absent_if_repository_is_empty() {
    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric1, SOME_RULE)).isAbsent();
  }

  @Test
  public void getRawMeasure_for_rule_returns_measure_for_specified_rule() {
    Measure measure = Measure.newMeasure().forRule(SOME_RULE.getId()).createNoValue();

    underTest.add(FILE_COMPONENT, metric1, measure);
    underTest.add(FILE_COMPONENT, metric1, Measure.newMeasure().forRule(222).createNoValue());

    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric1, SOME_RULE).get()).isSameAs(measure);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_characteristic_throws_NPE_if_Component_arg_is_null() {
    underTest.getRawMeasure(null, metric1, SOME_CHARACTERISTIC);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_characteristic_throws_NPE_if_Metric_arg_is_null() {
    underTest.getRawMeasure(FILE_COMPONENT, null, SOME_CHARACTERISTIC);
  }

  @Test(expected = NullPointerException.class)
  public void getRawMeasure_for_characteristic_throws_NPE_if_Characteristic_arg_is_null() {
    underTest.getRawMeasure(FILE_COMPONENT, metric1, (Characteristic) null);
  }

  @Test
  public void getRawMeasure_for_characteristic_returns_absent_if_repository_is_empty() {
    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric1, SOME_CHARACTERISTIC)).isAbsent();
  }

  @Test
  public void getRawMeasure_for_characteristic_returns_measure_for_specified_rule() {
    Measure measure = Measure.newMeasure().forCharacteristic(SOME_CHARACTERISTIC.getId()).createNoValue();

    underTest.add(FILE_COMPONENT, metric1, measure);
    underTest.add(FILE_COMPONENT, metric1, Measure.newMeasure().forCharacteristic(333).createNoValue());

    assertThat(underTest.getRawMeasure(FILE_COMPONENT, metric1, SOME_CHARACTERISTIC).get()).isSameAs(measure);
  }

  @Test
  public void getRawMeasures_returns_added_measures_over_batch_measures() {
    BatchReport.Measure batchMeasure1 = BatchReport.Measure.newBuilder().setMetricKey(METRIC_KEY_1).setStringValue("some value").build();
    BatchReport.Measure batchMeasure2 = BatchReport.Measure.newBuilder().setMetricKey(METRIC_KEY_2).setStringValue("some value").build();
    reportReader.putMeasures(FILE_COMPONENT.getRef(), ImmutableList.of(batchMeasure1, batchMeasure2));

    Measure addedMeasure = SOME_MEASURE;
    underTest.add(FILE_COMPONENT, metric1, addedMeasure);
    Measure addedMeasure2 = Measure.newMeasure().forCharacteristic(SOME_CHARACTERISTIC.getId()).createNoValue();
    underTest.add(FILE_COMPONENT, metric1, addedMeasure2);

    SetMultimap<String, Measure> rawMeasures = underTest.getRawMeasures(FILE_COMPONENT);

    assertThat(rawMeasures.keySet()).hasSize(2);
    assertThat(rawMeasures.get(METRIC_KEY_1)).containsOnly(addedMeasure, addedMeasure2);
    assertThat(rawMeasures.get(METRIC_KEY_2)).containsOnly(Measure.newMeasure().create("some value"));
  }

  private static MeasureDto createMeasureDto(int metricId, long snapshotId) {
    return new MeasureDto()
      .setComponentId(COMPONENT_ID)
      .setSnapshotId(snapshotId)
      .setData(SOME_DATA)
      .setMetricId(metricId);
  }
}
