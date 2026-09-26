package ru.yandex.practicum.analyzer.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

@Getter
@Setter
@EqualsAndHashCode
public class ScenarioActionId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long scenario;
    private String sensor;
    private Long action;
}
