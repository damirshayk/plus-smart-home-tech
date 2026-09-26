package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.yandex.practicum.analyzer.model.ScenarioCondition;
import ru.yandex.practicum.analyzer.model.ScenarioConditionId;

import java.util.Collection;
import java.util.List;

public interface ScenarioConditionRepository extends JpaRepository<ScenarioCondition, ScenarioConditionId> {

    @Query("select distinct sc.condition.id from ScenarioCondition sc where sc.scenario.id in :scenarioIds")
    List<Long> findConditionIdsByScenarioIds(@Param("scenarioIds") Collection<Long> scenarioIds);

    @Modifying
    @Query("delete from ScenarioCondition sc where sc.scenario.id in :scenarioIds")
    void deleteByScenarioIds(@Param("scenarioIds") Collection<Long> scenarioIds);
}
