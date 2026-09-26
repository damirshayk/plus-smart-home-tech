package ru.yandex.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.analyzer.model.ScenarioCondition;
import ru.yandex.practicum.analyzer.model.ScenarioConditionId;

public interface ScenarioConditionRepository extends JpaRepository<ScenarioCondition, ScenarioConditionId> {
}
