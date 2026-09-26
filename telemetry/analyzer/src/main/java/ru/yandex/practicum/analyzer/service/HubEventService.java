package ru.yandex.practicum.analyzer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.analyzer.model.Action;
import ru.yandex.practicum.analyzer.model.ActionType;
import ru.yandex.practicum.analyzer.model.Condition;
import ru.yandex.practicum.analyzer.model.ConditionOperation;
import ru.yandex.practicum.analyzer.model.ConditionType;
import ru.yandex.practicum.analyzer.model.Scenario;
import ru.yandex.practicum.analyzer.model.ScenarioAction;
import ru.yandex.practicum.analyzer.model.ScenarioCondition;
import ru.yandex.practicum.analyzer.model.Sensor;
import ru.yandex.practicum.analyzer.repository.ActionRepository;
import ru.yandex.practicum.analyzer.repository.ConditionRepository;
import ru.yandex.practicum.analyzer.repository.ScenarioActionRepository;
import ru.yandex.practicum.analyzer.repository.ScenarioConditionRepository;
import ru.yandex.practicum.analyzer.repository.ScenarioRepository;
import ru.yandex.practicum.analyzer.repository.SensorRepository;
import ru.yandex.practicum.kafka.telemetry.event.DeviceActionAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceAddedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceRemovedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioAddedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioConditionAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioRemovedEventAvro;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HubEventService {

    private final SensorRepository sensorRepository;
    private final ScenarioRepository scenarioRepository;
    private final ConditionRepository conditionRepository;
    private final ActionRepository actionRepository;
    private final ScenarioConditionRepository scenarioConditionRepository;
    private final ScenarioActionRepository scenarioActionRepository;

    @Transactional
    public void handle(HubEventAvro event) {
        require(event != null, "Событие хаба не должно быть null");
        String hubId = event.getHubId();
        requireText(hubId, "Идентификатор хаба");

        switch (event.getPayload()) {
            case DeviceAddedEventAvro added -> addDevice(hubId, added);
            case DeviceRemovedEventAvro removed -> removeDevice(hubId, removed.getId());
            case ScenarioAddedEventAvro added -> saveScenario(hubId, added);
            case ScenarioRemovedEventAvro removed -> removeScenario(hubId, removed.getName());
            case null, default -> throw new IllegalArgumentException("Неизвестные данные события хаба");
        }
    }

    private void addDevice(String hubId, DeviceAddedEventAvro event) {
        requireText(event.getId(), "Идентификатор устройства");
        require(event.getType() != null, "Тип устройства должен быть указан");
        if (sensorRepository.insertIfAbsent(event.getId(), hubId) == 0) {
            require(sensorRepository.findByIdAndHubId(event.getId(), hubId).isPresent(),
                    "Устройство уже принадлежит другому хабу");
        }
    }

    private void removeDevice(String hubId, String sensorId) {
        requireText(sensorId, "Идентификатор устройства");
        deleteScenarios(scenarioRepository.findIdsByHubIdAndSensorId(hubId, sensorId));
        sensorRepository.deleteByIdAndHubId(sensorId, hubId);
    }

    private void removeScenario(String hubId, String name) {
        requireText(name, "Название сценария");
        scenarioRepository.findByHubIdAndName(hubId, name)
                .ifPresent(scenario -> deleteScenarios(List.of(scenario.getId())));
    }

    private void saveScenario(String hubId, ScenarioAddedEventAvro event) {
        requireText(event.getName(), "Название сценария");
        require(event.getConditions() != null, "Список условий должен быть указан");
        require(event.getActions() != null, "Список действий должен быть указан");

        Set<String> sensorIds = new LinkedHashSet<>();
        List<Condition> conditions = new ArrayList<>();
        for (ScenarioConditionAvro source : event.getConditions()) {
            require(source != null, "Условие не должно быть null");
            requireText(source.getSensorId(), "Идентификатор датчика условия");
            require(source.getType() != null && source.getOperation() != null,
                    "Тип и операция условия должны быть указаны");
            sensorIds.add(source.getSensorId());
            Condition condition = new Condition();
            condition.setType(ConditionType.valueOf(source.getType().name()));
            condition.setOperation(ConditionOperation.valueOf(source.getOperation().name()));
            condition.setValue(toInteger(source.getValue()));
            conditions.add(condition);
        }

        List<Action> actions = new ArrayList<>();
        for (DeviceActionAvro source : event.getActions()) {
            require(source != null, "Действие не должно быть null");
            requireText(source.getSensorId(), "Идентификатор устройства действия");
            require(source.getType() != null, "Тип действия должен быть указан");
            sensorIds.add(source.getSensorId());
            Action action = new Action();
            action.setType(ActionType.valueOf(source.getType().name()));
            action.setValue(source.getValue());
            actions.add(action);
        }

        Map<String, Sensor> sensors = new HashMap<>();
        if (!sensorIds.isEmpty()) {
            sensorRepository.findByIdInAndHubId(sensorIds, hubId)
                    .forEach(sensor -> sensors.put(sensor.getId(), sensor));
        }
        require(sensors.size() == sensorIds.size(),
                "Все устройства сценария должны быть зарегистрированы в указанном хабе");

        scenarioRepository.findByHubIdAndName(hubId, event.getName())
                .ifPresent(scenario -> deleteScenarios(List.of(scenario.getId())));
        conditionRepository.saveAll(conditions);
        actionRepository.saveAll(actions);

        Scenario scenario = new Scenario();
        scenario.setHubId(hubId);
        scenario.setName(event.getName());
        for (int i = 0; i < conditions.size(); i++) {
            ScenarioCondition link = new ScenarioCondition();
            link.setScenario(scenario);
            link.setSensor(sensors.get(event.getConditions().get(i).getSensorId()));
            link.setCondition(conditions.get(i));
            scenario.getConditions().add(link);
        }
        for (int i = 0; i < actions.size(); i++) {
            ScenarioAction link = new ScenarioAction();
            link.setScenario(scenario);
            link.setSensor(sensors.get(event.getActions().get(i).getSensorId()));
            link.setAction(actions.get(i));
            scenario.getActions().add(link);
        }
        scenarioRepository.save(scenario);
    }

    private void deleteScenarios(Collection<Long> scenarioIds) {
        if (scenarioIds.isEmpty()) {
            return;
        }
        List<Long> conditionIds = scenarioConditionRepository.findConditionIdsByScenarioIds(scenarioIds);
        List<Long> actionIds = scenarioActionRepository.findActionIdsByScenarioIds(scenarioIds);
        scenarioConditionRepository.deleteByScenarioIds(scenarioIds);
        scenarioActionRepository.deleteByScenarioIds(scenarioIds);
        scenarioRepository.deleteAllByIdInBatch(scenarioIds);
        if (!conditionIds.isEmpty()) {
            conditionRepository.deleteUnreferencedByIds(conditionIds);
        }
        if (!actionIds.isEmpty()) {
            actionRepository.deleteUnreferencedByIds(actionIds);
        }
    }

    private Integer toInteger(Object value) {
        return switch (value) {
            case null -> null;
            case Integer number -> number;
            case Boolean flag -> flag ? 1 : 0;
            default -> throw new IllegalArgumentException("Неизвестный тип значения условия");
        };
    }

    private void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + " не должен быть пустым");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
