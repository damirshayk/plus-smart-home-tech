package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.yandex.practicum.analyzer.model.ScenarioAction;
import ru.yandex.practicum.analyzer.model.ScenarioActionId;

import java.util.Collection;
import java.util.List;

public interface ScenarioActionRepository extends JpaRepository<ScenarioAction, ScenarioActionId> {

    @Query("select distinct sa.action.id from ScenarioAction sa where sa.scenario.id in :scenarioIds")
    List<Long> findActionIdsByScenarioIds(@Param("scenarioIds") Collection<Long> scenarioIds);

    @Modifying
    @Query("delete from ScenarioAction sa where sa.scenario.id in :scenarioIds")
    void deleteByScenarioIds(@Param("scenarioIds") Collection<Long> scenarioIds);
}
