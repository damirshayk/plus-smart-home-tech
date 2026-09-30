package ru.yandex.practicum.analyzer.service;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.analyzer.model.Action;
import ru.yandex.practicum.analyzer.model.Scenario;
import ru.yandex.practicum.analyzer.model.ScenarioAction;
import ru.yandex.practicum.analyzer.repository.ScenarioRepository;
import ru.yandex.practicum.grpc.telemetry.event.ActionTypeProto;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionProto;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final ScenarioRepository scenarioRepository;
    private final ConditionEvaluator conditionEvaluator;
    private final HubRouterClient hubRouterClient;

    public List<DeviceActionRequest> prepare(SensorsSnapshotAvro snapshot) {
        if (snapshot == null || snapshot.getHubId() == null || snapshot.getHubId().isBlank()
                || snapshot.getTimestamp() == null || snapshot.getSensorsState() == null) {
            throw new IllegalArgumentException("Снимок должен содержать hubId, timestamp и состояния датчиков");
        }
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(snapshot.getTimestamp().getEpochSecond())
                .setNanos(snapshot.getTimestamp().getNano())
                .build();
        List<DeviceActionRequest> requests = new ArrayList<>();
        for (Scenario scenario : scenarioRepository.findByHubId(snapshot.getHubId())) {
            if (!scenario.getConditions().stream()
                    .allMatch(condition -> conditionEvaluator.matches(condition, snapshot.getSensorsState()))) {
                continue;
            }
            for (ScenarioAction action : scenario.getActions()) {
                try {
                    requests.add(toRequest(scenario, action, timestamp));
                } catch (IllegalArgumentException e) {
                    log.warn("Пропущено некорректное действие: hubId={}, scenarioId={}: {}",
                            snapshot.getHubId(), scenario.getId(), e.getMessage());
                }
            }
        }
        return List.copyOf(requests);
    }

    public void send(DeviceActionRequest request) {
        hubRouterClient.send(request);
    }

    private DeviceActionRequest toRequest(Scenario scenario, ScenarioAction link, Timestamp timestamp) {
        Action action = link == null ? null : link.getAction();
        if (action == null || action.getType() == null || link.getSensor() == null
                || link.getSensor().getId() == null || link.getSensor().getId().isBlank()
                || scenario.getName() == null || scenario.getName().isBlank()
                || scenario.getHubId() == null || scenario.getHubId().isBlank()) {
            throw new IllegalArgumentException("Сценарий содержит неполное действие");
        }
        DeviceActionProto.Builder command = DeviceActionProto.newBuilder()
                .setSensorId(link.getSensor().getId())
                .setType(ActionTypeProto.valueOf(action.getType().name()));
        if (action.getValue() != null) {
            command.setValue(action.getValue());
        }
        return DeviceActionRequest.newBuilder()
                .setHubId(scenario.getHubId())
                .setScenarioName(scenario.getName())
                .setAction(command)
                .setTimestamp(timestamp)
                .build();
    }
}
