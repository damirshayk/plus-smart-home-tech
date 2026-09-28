package ru.yandex.practicum.analyzer.service;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.analyzer.model.Action;
import ru.yandex.practicum.analyzer.model.Scenario;
import ru.yandex.practicum.analyzer.model.ScenarioAction;
import ru.yandex.practicum.analyzer.repository.ScenarioRepository;
import ru.yandex.practicum.grpc.telemetry.event.ActionTypeProto;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionProto;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final ScenarioRepository scenarioRepository;
    private final ConditionEvaluator conditionEvaluator;
    private final HubRouterClient hubRouterClient;

    public void handle(SensorsSnapshotAvro snapshot) {
        if (snapshot == null || snapshot.getHubId() == null || snapshot.getHubId().isBlank()
                || snapshot.getTimestamp() == null || snapshot.getSensorsState() == null) {
            throw new IllegalArgumentException("Снимок должен содержать hubId, timestamp и состояния датчиков");
        }
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(snapshot.getTimestamp().getEpochSecond())
                .setNanos(snapshot.getTimestamp().getNano())
                .build();
        List<DeviceActionRequest> requests = scenarioRepository.findByHubId(snapshot.getHubId()).stream()
                .filter(scenario -> scenario.getConditions().stream()
                        .allMatch(condition -> conditionEvaluator.matches(condition, snapshot.getSensorsState())))
                .flatMap(scenario -> scenario.getActions().stream()
                        .map(action -> toRequest(scenario, action, timestamp)))
                .toList();
        requests.forEach(hubRouterClient::send);
    }

    private DeviceActionRequest toRequest(Scenario scenario, ScenarioAction link, Timestamp timestamp) {
        Action action = link.getAction();
        if (action == null || action.getType() == null || link.getSensor() == null
                || link.getSensor().getId() == null || scenario.getName() == null) {
            throw new IllegalStateException("Сценарий содержит неполное действие: " + scenario.getId());
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
