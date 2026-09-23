package ru.yandex.practicum.collector.model.hub;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ScenarioCondition {

    private String sensorId;
    private ConditionType type;
    private ConditionOperation operation;
    private Integer value;
}