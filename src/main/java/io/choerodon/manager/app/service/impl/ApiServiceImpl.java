package io.choerodon.manager.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.manager.domain.service.ISwaggerService;
import io.choerodon.manager.infra.dataobject.RouteDO;
import io.choerodon.manager.infra.mapper.RouteMapper;
import org.apache.commons.collections.MapIterator;
import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.commons.collections.map.MultiKeyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.swagger.PermissionData;
import io.choerodon.core.swagger.SwaggerExtraData;
import io.choerodon.manager.api.dto.swagger.*;
import io.choerodon.manager.app.service.ApiService;
import io.choerodon.manager.domain.manager.entity.MyLinkedList;
import io.choerodon.manager.domain.service.IDocumentService;
import io.choerodon.manager.infra.common.utils.ManualPageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import springfox.documentation.swagger.web.SwaggerResource;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author superlee
 */
@Service
public class ApiServiceImpl implements ApiService {

    private static final Logger logger = LoggerFactory.getLogger(ApiServiceImpl.class);

    private static final String DESCRIPTION = "description";

    private static final String TITLE = "title";
    private static final String KEY = "key";
    private static final String CHILDREN = "children";
    private static final String API_TREE_DOC = "api-tree-doc";
    private static final String PATH_DETAIL = "path-detail";
    private static final String COLON = ":";

    private IDocumentService iDocumentService;

    private RouteMapper routeMapper;

    private ISwaggerService iSwaggerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StringRedisTemplate redisTemplate;

    public ApiServiceImpl(IDocumentService iDocumentService, RouteMapper routeMapper, ISwaggerService iSwaggerService, StringRedisTemplate redisTemplate) {
        this.iDocumentService = iDocumentService;
        this.routeMapper = routeMapper;
        this.iSwaggerService = iSwaggerService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Page<ControllerDTO> getControllers(String name, String version, PageRequest pageRequest, Map<String, Object> map) {
        String json = getSwaggerJson(name, version);
        return Optional.ofNullable(json)
                .map(j -> ManualPageHelper.postPage(processJson2ControllerDTO(name, j), pageRequest, map))
                .orElseThrow(() -> new CommonException("error.service.swaggerJson.empty"));
    }

    @Override
    public String getSwaggerJson(String name, String version) {
        String serviceName = getRouteName(name);
        String json = iDocumentService.fetchSwaggerJsonByService(serviceName, version);
        try {
            if (json != null) {
                //自定义扩展swaggerJson
                json = iDocumentService.expandSwaggerJson(name, version, json);
            }
        } catch (IOException e) {
            logger.error("fetch swagger json error, service: {}, version: {}, exception: {}", name, version, e.getMessage());
            throw new CommonException(e, "error.service.not.run", name, version);
        }
        return json;
    }

    @Override
    public Map<String, Object> queryServiceInvoke(String beginDate, String endDate) {
        MultiKeyMap multiKeyMap = getServiceMap();
        MapIterator mapIterator = multiKeyMap.mapIterator();
        Set<String> serviceKeySet = new HashSet<>();
        while (mapIterator.hasNext()) {
            MultiKey multiKey = (MultiKey) mapIterator.next();
            Object[] keys = multiKey.getKeys();
            serviceKeySet.add((String) keys[1]);
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (String service : serviceKeySet) {
            Map<String, Object> detailMap = new HashMap<>(2);
            detailMap.put("service", service);
            detailMap.put("data", new ArrayList<>());
            details.add(detailMap);
        }
        Map<String, Object> map = new HashMap<>();
        Set<String> date = new LinkedHashSet<>();
        map.put("date", date);
        map.put("details", details);
        validateDate(beginDate);
        validateDate(endDate);
        Map<String, Integer> lastDayServiceCount = setDetails(beginDate, endDate, details, date);
        List<String> sortedKey =
                lastDayServiceCount.entrySet()
                        .stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
        map.put("services", sortedKey);
        return map;
    }

    private Map<String, Integer> setDetails(String beginDate, String endDate, List<Map<String, Object>> details, Set<String> date) {
        try {
            Map<String, Integer> lastDayServiceCount = new HashMap<>();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            Date begin = dateFormat.parse(beginDate);
            Date end = dateFormat.parse(endDate);
            if (begin.after(end)) {
                throw new CommonException("error.date.order");
            }
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(begin);
            while (true) {
                if (calendar.getTime().after(end)) {
                    break;
                }
                String dateStr = dateFormat.format(calendar.getTime());
                date.add(dateStr);
                String value = redisTemplate.opsForValue().get(dateStr);
                if (StringUtils.isEmpty(value)) {
                    details.forEach(m -> {
                        ((List<Integer>) m.get("data")).add(0);
                        lastDayServiceCount.put((String) m.get("service"), 0);
                    });
                } else {
                    try {
                        Map<String, Integer> serviceMap = objectMapper.readValue(value, new TypeReference<Map<String, Integer>>() {
                        });
                        details.forEach(m -> {
                            String service = (String) m.get("service");
                            List<Integer> list = (List<Integer>) m.get("data");
                            int count = serviceMap.get(service) == null ? 0 : serviceMap.get(service);
                            list.add(count);
                            lastDayServiceCount.put(service, count);
                        });
                    } catch (IOException e) {
                        logger.error("object mapper read value to map error, redis key {}, value {}, exception :: {}", dateStr, value, e);
                    }
                }
                calendar.add(Calendar.DATE, 1);
            }
            return lastDayServiceCount;
        } catch (ParseException e) {
            throw new CommonException("error.date.parse", beginDate, endDate);
        }
    }

    @Override
    public Map<String, Object> queryApiInvoke(String beginDate, String endDate, String service) {
        Map<String, Object> map = new HashMap<>();
        Set<String> date = new LinkedHashSet<>();
        List<Map<String, Object>> details = new ArrayList<>();
        Set<String> keySet = new HashSet<>();
        map.put("date", date);
        map.put("details", details);
        validateDate(beginDate);
        validateDate(endDate);
        Map<String, Map<String, Integer>> dateMap = new HashMap<>();
        setDataMapAndKeySet(beginDate, endDate, service, date, keySet, dateMap);
        Map<String, Integer> lastDayApiCount = new HashMap<>();
        for (String api : keySet) {
            Map<String, Object> detailMap = new HashMap<>(2);
            detailMap.put("api", api);
            List<Integer> data = new ArrayList<>();
            detailMap.put("data", data);
            for (String dateStr : date) {
                Map<String, Integer> apiMap = dateMap.get(dateStr);
                if (apiMap == null) {
                    data.add(0);
                    lastDayApiCount.put(api, 0);
                } else {
                    int count = apiMap.get(api) == null ? 0 : apiMap.get(api);
                    data.add(count);
                    lastDayApiCount.put(api, count);
                }
            }
            details.add(detailMap);
        }
        List<String> sortedKey =
                lastDayApiCount.entrySet()
                        .stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
        map.put("apis", sortedKey);
        return map;

    }

    @Override
    public Map queryTreeMenu() {
        MultiKeyMap multiKeyMap = getServiceMap();
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        map.put("service", list);
        int serviceCount = 0;
        MapIterator mapIterator = multiKeyMap.mapIterator();
        while (mapIterator.hasNext()) {
            MultiKey multiKey = (MultiKey) mapIterator.next();
            Object[] keys = multiKey.getKeys();
            String routeName = (String) keys[0];
            String service = (String) keys[1];
            Map<String, Object> serviceMap = new HashMap<>();
            list.add(serviceMap);
            Set<String> versions = (Set<String>) multiKeyMap.get(routeName, service);
            serviceMap.put(TITLE, service);
            String serviceKey = serviceCount + "";
            serviceMap.put(KEY, serviceKey);
            List<Map<String, Object>> children = new ArrayList<>();
            serviceMap.put(CHILDREN, children);
            processTreeOnVersionNode(routeName, service, versions, children, serviceKey);
            serviceCount++;
        }
        return map;
    }

    private void processTreeOnVersionNode(String routeName, String service, Set<String> versions, List<Map<String, Object>> children, String parentKey) {
        int versionCount = 0;
        for (String version : versions) {
            Map<String, Object> versionMap = new HashMap<>();
            children.add(versionMap);
            versionMap.put(TITLE, version);
            String versionKey = parentKey + "-" + versionCount;
            versionMap.put(KEY, versionKey);
            List<Map<String, Object>> versionChildren = new ArrayList<>();
            versionMap.put(CHILDREN, versionChildren);
            String apiTreeDocKey = getApiTreeDocKey(service, version);
            if (redisTemplate.hasKey(apiTreeDocKey)) {
                String childrenStr = redisTemplate.opsForValue().get(apiTreeDocKey);
                try {
                    List<Map<String, Object>> list =
                            objectMapper.readValue(childrenStr, new TypeReference<List<Map<String, Object>>>() {
                            });
                    versionChildren.addAll(list);
                } catch (IOException e) {
                    logger.error("object mapper read redis cache value {} to List<Map<String, Object>> error, so process children version from db or swagger, exception: {} ", childrenStr, e);
                    processChildrenFromSwaggerJson(routeName, service, version, versionKey, versionChildren);
                }
            } else {
                processChildrenFromSwaggerJson(routeName, service, version, versionKey, versionChildren);
            }
            versionCount++;
        }
    }

    private void processChildrenFromSwaggerJson(String routeName, String service, String version, String versionKey, List<Map<String, Object>> versionChildren) {
        String json = iDocumentService.fetchSwaggerJsonByService(service, version);
        if (StringUtils.isEmpty(json)) {
            logger.warn("the swagger json of service {} version {} is empty, skip", service, version);
        } else {
            try {
                JsonNode node = objectMapper.readTree(json);
                processTreeOnControllerNode(routeName, service, version, node, versionChildren, versionKey);
            } catch (IOException e) {
                logger.error("object mapper read tree error, service: {}, version: {}", service, version);
            }
        }
    }

    private void processTreeOnControllerNode(String routeName, String service, String version, JsonNode node, List<Map<String, Object>> children, String parentKey) {
        Map<String, Map> controllerMap = processControllerMap(node);
        Map<String, List> pathMap = processPathMap(routeName, service, version, node);
        int controllerCount = 0;
        for (Map.Entry<String, Map> entry : controllerMap.entrySet()) {
            int pathCount = 0;
            String controllerName = entry.getKey();
            Map<String, Object> controller = entry.getValue();
            List<Map<String, Object>> controllerChildren = (List<Map<String, Object>>) controller.get(CHILDREN);
            List<Map<String, Object>> list = pathMap.get(controllerName);
            if (list != null) {
                String controllerKey = parentKey + "-" + controllerCount;
                controller.put(KEY, controllerKey);
                children.add(controller);
                for (Map<String, Object> path : list) {
                    path.put(KEY, controllerKey + "-" + pathCount);
                    path.put("refController", controllerName);
                    controllerChildren.add(path);
                    pathCount++;
                }
                controllerCount++;
            }
        }
        cache2Redis(getApiTreeDocKey(service, version), children);
    }

    private void cache2Redis(String key, Object value) {
        try {
            //缓存10天
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), 10, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            logger.warn("read object to string error while caching to redis, exception: {}", e);
        }
    }

    private String getApiTreeDocKey(String service, String version) {
        StringBuilder stringBuilder = new StringBuilder(API_TREE_DOC);
        stringBuilder.append(COLON).append(service).append(COLON).append(version);
        return stringBuilder.toString();
    }

    private Map<String, List> processPathMap(String routeName, String service, String version, JsonNode node) {
        Map<String, List> pathMap = new HashMap<>();
        JsonNode pathNode = node.get("paths");
        Iterator<String> urlIterator = pathNode.fieldNames();
        while (urlIterator.hasNext()) {
            String url = urlIterator.next();
            JsonNode methodNode = pathNode.get(url);
            Iterator<String> methodIterator = methodNode.fieldNames();
            while (methodIterator.hasNext()) {
                String method = methodIterator.next();
                JsonNode jsonNode = methodNode.findValue(method);
                if (jsonNode.get("description") == null) {
                    continue;
                }
                Map<String, Object> path = new HashMap<>();
                path.put(TITLE, url);
                path.put("method", method);
                path.put("operationId", Optional.ofNullable(jsonNode.get("operationId")).map(JsonNode::asText).orElse(null));
                path.put("service", service);
                path.put("version", version);
                path.put("servicePrefix", routeName);
                JsonNode tagNode = jsonNode.get("tags");
                for (int i = 0; i < tagNode.size(); i++) {
                    String tag = tagNode.get(i).asText();
                    if (pathMap.get(tag) == null) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        list.add(path);
                        pathMap.put(tag, list);
                    } else {
                        pathMap.get(tag).add(path);
                    }
                }
            }
        }
        return pathMap;
    }

    private Map<String, Map> processControllerMap(JsonNode node) {
        Map<String, Map> controllerMap = new HashMap<>();
        JsonNode tagNodes = node.get("tags");
        Iterator<JsonNode> iterator = tagNodes.iterator();
        while (iterator.hasNext()) {
            JsonNode jsonNode = iterator.next();
            String name = jsonNode.findValue("name").asText();
            if (!name.contains("-controller") && !name.contains("-endpoint")) {
                continue;
            }
            Map<String, Object> controller = new HashMap<>();
            controllerMap.put(name, controller);
            controller.put(TITLE, name);
            List<Map<String, Object>> controllerChildren = new ArrayList<>();
            controller.put(CHILDREN, controllerChildren);
        }
        return controllerMap;
    }

    private void setDataMapAndKeySet(String beginDate, String endDate, String service, Set<String> date, Set<String> keySet, Map<String, Map<String, Integer>> dateMap) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            Date begin = dateFormat.parse(beginDate);
            Date end = dateFormat.parse(endDate);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(begin);
            if (begin.after(end)) {
                throw new CommonException("error.date.order");
            }
            while (true) {
                if (calendar.getTime().after(end)) {
                    break;
                }
                String dateStr = dateFormat.format(calendar.getTime());
                date.add(dateStr);
                String value = redisTemplate.opsForValue().get(dateStr + COLON + service);
                if (StringUtils.isEmpty(value)) {
                    dateMap.put(dateStr, null);
                } else {
                    try {
                        Map<String, Integer> apiMap =
                                objectMapper.readValue(value, new TypeReference<Map<String, Integer>>() {
                                });
                        keySet.addAll(apiMap.keySet());
                        dateMap.put(dateStr, apiMap);
                    } catch (IOException e) {
                        logger.error("object mapper read value to map error, redis key {}, value {}, exception :: {}", dateStr, value, e);
                    }
                }
                calendar.add(Calendar.DATE, 1);
            }
        } catch (ParseException e) {
            throw new CommonException("error.date.parse", beginDate, endDate);
        }
    }

    /**
     * @return MultiKeyMap, key1 is route name, key2 is service id, value is version set
     */
    private MultiKeyMap getServiceMap() {
        MultiKeyMap multiKeyMap = new MultiKeyMap();
        List<SwaggerResource> resources = iSwaggerService.getSwaggerResource();
        for (SwaggerResource resource : resources) {
            String name = resource.getName();
            String[] nameArray = name.split(COLON);
            String location = resource.getLocation();
            String[] locationArray = location.split("\\?version=");
            if (nameArray.length != 2 || locationArray.length != 2) {
                logger.warn("the resource name is not match xx:xx or location is not match /doc/xx?version=xxx , name : {}, location: {}", name, location);
                continue;
            }
            String routeName = nameArray[0];
            String service = nameArray[1];
            String version = locationArray[1];
            if (multiKeyMap.get(routeName, service) == null) {
                Set<String> versionSet = new HashSet<>();
                versionSet.add(version);
                multiKeyMap.put(routeName, service, versionSet);
            } else {
                Set<String> versionSet = (Set<String>) multiKeyMap.get(routeName, service);
                versionSet.add(version);
            }
        }
        return multiKeyMap;
    }

    private void validateDate(String date) {
        String dateRegex = "\\d{4}-\\d{2}-\\d{2}";
        if (!Pattern.matches(dateRegex, date)) {
            throw new CommonException("error.date.format");
        }
    }

    private String getRouteName(String name) {
        String serviceName;
        RouteDO routeDO = new RouteDO();
        routeDO.setName(name);
        RouteDO route = routeMapper.selectOne(routeDO);
        if (route == null) {
            throw new CommonException("error.route.not.found.routeName{" + name + "}");
        } else {
            serviceName = route.getServiceId();
        }
        return serviceName;
    }

    @Override
    public ControllerDTO queryPathDetail(String serviceName, String version, String controllerName, String operationId) {
        String key = getPathDetailRedisKey(serviceName, version, controllerName, operationId);
        if (redisTemplate.hasKey(key)) {
            String value = redisTemplate.opsForValue().get(key);
            try {
                return objectMapper.readValue(value, ControllerDTO.class);
            } catch (IOException e) {
                logger.error("object mapper read redis cache value {} to ControllerDTO error, so process from db or swagger, exception: {} ", value, e);
            }
        }
        try {
            return processPathDetailFromSwagger(serviceName, version, controllerName, operationId, key);
        } catch (IOException e) {
            logger.error("fetch swagger json error, service: {}, version: {}, exception: {}", serviceName, version, e.getMessage());
            throw new CommonException("error.service.not.run", serviceName, version);
        }

    }

    private ControllerDTO processPathDetailFromSwagger(String name, String version, String controllerName, String operationId, String key) throws IOException {
        String json = getSwaggerJson(name, version);
        JsonNode node = objectMapper.readTree(json);
        List<ControllerDTO> controllers = processControllers(node);
        List<ControllerDTO> targetControllers =
                controllers.stream().filter(c -> controllerName.equals(c.getName())).collect(Collectors.toList());
        if (targetControllers.isEmpty()) {
            throw new CommonException("error.controller.not.found", controllerName);
        }
        Map<String, Map<String, FieldDTO>> map = processDefinitions(node);
        Map<String, String> dtoMap = convertMap2JsonWithComments(map);
        JsonNode pathNode = node.get("paths");
        String basePath = node.get("basePath").asText();
        ControllerDTO controller = queryPathDetailByOptions(name, pathNode, targetControllers, operationId, dtoMap, basePath);
        cache2Redis(key, controller);
        return controller;
    }

    private String getPathDetailRedisKey(String name, String version, String controllerName, String operationId) {
        StringBuilder builder = new StringBuilder(PATH_DETAIL);
        builder
                .append(COLON)
                .append(name)
                .append(COLON)
                .append(version)
                .append(COLON)
                .append(controllerName)
                .append(COLON)
                .append(operationId);
        return builder.toString();
    }

    @Override
    public Map<String, Object> queryInstancesAndApiCount() {
        Map<String, Object> apiCountMap = new HashMap<>(2);
        List<String> services = new ArrayList<>();
        List<Integer> apiCounts = new ArrayList<>();
        apiCountMap.put("services", services);
        apiCountMap.put("apiCounts", apiCounts);
        MultiKeyMap multiKeyMap = getServiceMap();
        MapIterator mapIterator = multiKeyMap.mapIterator();
        while (mapIterator.hasNext()) {
            MultiKey multiKey = (MultiKey) mapIterator.next();
            Object[] keys = multiKey.getKeys();
            String routeName = (String) keys[0];
            String service = (String) keys[1];
            Set<String> versions = (Set<String>) multiKeyMap.get(routeName, service);
            int count = 0;
            //目前只有一个版本，所以取第一个，如果后续支持多版本，此处遍历版本即可
            Iterator<String> iterator = versions.iterator();
            String version = null;
            while (iterator.hasNext()) {
                version = iterator.next();
                break;
            }
            if (version != null) {
                String json = iDocumentService.fetchSwaggerJsonByService(service, version);
                if (StringUtils.isEmpty(json)) {
                    logger.warn("the swagger json of service {} version {} is empty, skip", service, version);
                } else {
                    try {
                        JsonNode node = objectMapper.readTree(json);
                        JsonNode pathNode = node.get("paths");
                        Iterator<String> urlIterator = pathNode.fieldNames();
                        while (urlIterator.hasNext()) {
                            String url = urlIterator.next();
                            JsonNode methodNode = pathNode.get(url);
                            count = count + methodNode.size();
                        }
                    } catch (IOException e) {
                        logger.error("object mapper read tree error, service: {}, version: {}", service, version);
                    }
                }
            }
            services.add(service);
            apiCounts.add(count);
        }
        return apiCountMap;
    }

    private List<ControllerDTO> processJson2ControllerDTO(String serviceName, String json) {
        List<ControllerDTO> controllers;
        try {
            JsonNode node = objectMapper.readTree(json);
            //解析definitions,构造json
            String basePath = node.get("basePath").asText();
            Map<String, Map<String, FieldDTO>> map = processDefinitions(node);
            Map<String, String> dtoMap = convertMap2JsonWithComments(map);
            controllers = processControllers(node);
            JsonNode pathNode = node.get("paths");
            processPaths(serviceName, pathNode, controllers, dtoMap, basePath);
        } catch (IOException e) {
            throw new CommonException("error.parseJson");
        }
        return controllers;
    }

    private Map<String, String> convertMap2JsonWithComments(Map<String, Map<String, FieldDTO>> map) {
        Map<String, String> returnMap = new HashMap<>();
        for (Map.Entry<String, Map<String, FieldDTO>> entry : map.entrySet()) {
            StringBuilder sb = new StringBuilder();
            String className = entry.getKey();
            //dto引用链表，用于判断是否有循环引用
            MyLinkedList<String> linkedList = new MyLinkedList<>();
            linkedList.addNode(className);
            process2String(className, map, sb, linkedList);
            returnMap.put(className, sb.toString());
        }
        return returnMap;
    }

    private void process2String(String ref, Map<String, Map<String, FieldDTO>> map, StringBuilder sb, MyLinkedList<String> linkedList) {
        for (Map.Entry<String, Map<String, FieldDTO>> entry : map.entrySet()) {
            String className = subString4ClassName(ref);
            if (className.equals(entry.getKey())) {
                sb.append("{\n");
                Map<String, FieldDTO> fileds = entry.getValue();
                //两个空格为缩进单位
                if (fileds != null) {
                    for (Map.Entry<String, FieldDTO> entry1 : fileds.entrySet()) {
                        String field = entry1.getKey();
                        FieldDTO dto = entry1.getValue();
                        //如果是集合类型，注释拼到字段的上一行
                        String type = dto.getType();
                        if ("array".equals(type)) {
                            //处理集合引用的情况，type为array
                            if (dto.getComment() != null) {
                                sb.append("//");
                                sb.append(dto.getComment());
                                sb.append("\n");
                            }
                            appendField(sb, field);
                            sb.append("[\n");
                            if (dto.getRef() != null) {
                                String refClassName = subString4ClassName(dto.getRef());
                                //linkedList深拷贝一份，处理同一个对象对另一个对象的多次引用的情况
                                MyLinkedList<String> copyLinkedList = linkedList.deepCopy();
                                copyLinkedList.addNode(refClassName);
                                //循环引用直接跳出递归
                                if (copyLinkedList.isLoop()) {
                                    sb.append("{}");
                                } else {
                                    //递归解析
                                    process2String(refClassName, map, sb, copyLinkedList);
                                }
                            } else {
                                sb.append(type);
                                sb.append("\n");
                            }
                            sb.append("]\n");
                        } else if (StringUtils.isEmpty(type)) {
                            //单一对象引用的情况，只有ref
                            if (dto.getRef() != null) {
                                if (dto.getComment() != null) {
                                    sb.append("//");
                                    sb.append(dto.getComment());
                                    sb.append("\n");
                                }
                                appendField(sb, field);
                                String refClassName = subString4ClassName(dto.getRef());
                                //linkedList深拷贝一份，处理同一个对象对另一个对象的多次引用的情况
                                MyLinkedList<String> copyLinkedList = linkedList.deepCopy();
                                copyLinkedList.addNode(refClassName);
                                //循环引用直接跳出递归
                                if (copyLinkedList.isLoop()) {
                                    sb.append("{}");
                                } else {
                                    //递归解析
                                    process2String(refClassName, map, sb, copyLinkedList);
                                }
                            } else {
                                sb.append("{}\n");
                            }
                        } else {
                            if ("integer".equals(type) || "string".equals(type) || "boolean".equals(type)) {
                                appendField(sb, field);
                                sb.append("\"");
                                sb.append(type);
                                sb.append("\"");
                                //拼注释
                                appendComment(sb, dto);
                                sb.append("\n");
                            }
                            if ("object".equals(type)) {
                                appendField(sb, field);
                                sb.append("\"{}\"");
                                //拼注释
                                appendComment(sb, dto);
                                sb.append("\n");
                            }
                        }
                    }
                }
                sb.append("}");
            }
        }
    }

    private String subString4ClassName(String ref) {
        //截取#/definitions/RouteDTO字符串，拿到类名
        String[] arr = ref.split("/");
        return arr[arr.length - 1];
    }

    private void appendField(StringBuilder sb, String field) {
        sb.append("\"");
        sb.append(field);
        sb.append("\"");
        sb.append(COLON);
    }

    private void appendComment(StringBuilder sb, FieldDTO dto) {
        if (dto.getComment() != null) {
            sb.append(" //");
            sb.append(dto.getComment());
        }
    }

    private List<ControllerDTO> processControllers(JsonNode node) {
        List<ControllerDTO> controllers = new ArrayList<>();
        JsonNode tagNodes = node.get("tags");
        Iterator<JsonNode> iterator = tagNodes.iterator();
        while (iterator.hasNext()) {
            JsonNode jsonNode = iterator.next();
            String name = jsonNode.findValue("name").asText();
            String description = jsonNode.findValue(DESCRIPTION).asText();
            ControllerDTO controller = new ControllerDTO();
            controller.setName(name);
            controller.setDescription(description);
            controller.setPaths(new ArrayList<>());
            controllers.add(controller);
        }
        return controllers;
    }

    private Map<String, Map<String, FieldDTO>> processDefinitions(JsonNode node) {
        Map<String, Map<String, FieldDTO>> map = new HashMap<>();
        //definitions节点是controller里面的对象json集合
        JsonNode definitionNodes = node.get("definitions");
        if (definitionNodes != null) {
            Iterator<String> classNameIterator = definitionNodes.fieldNames();
            while (classNameIterator.hasNext()) {
                String className = classNameIterator.next();
                JsonNode jsonNode = definitionNodes.get(className);
                JsonNode propertyNode = jsonNode.get("properties");
                if (propertyNode == null) {
                    String type = jsonNode.get("type").asText();
                    if ("object".equals(type)) {
                        map.put(className, null);
                    }
                    continue;
                }
                Iterator<String> filedNameIterator = propertyNode.fieldNames();
                Map<String, FieldDTO> fieldMap = new HashMap<>();
                while (filedNameIterator.hasNext()) {
                    FieldDTO field = new FieldDTO();
                    String filedName = filedNameIterator.next();
                    JsonNode fieldNode = propertyNode.get(filedName);
                    String type = Optional.ofNullable(fieldNode.get("type")).map(JsonNode::asText).orElse(null);
                    field.setType(type);
                    String description = Optional.ofNullable(fieldNode.get(DESCRIPTION)).map(JsonNode::asText).orElse(null);
                    field.setComment(description);
                    field.setRef(Optional.ofNullable(fieldNode.get("$ref")).map(JsonNode::asText).orElse(null));
                    JsonNode itemNode = fieldNode.get("items");
                    Optional.ofNullable(itemNode).ifPresent(i -> {
                        if (i.get("type") != null) {
                            field.setItemType(i.get("type").asText());
                        }
                        if (i.get("$ref") != null) {
                            field.setRef(i.get("$ref").asText());
                        }
                    });
                    fieldMap.put(filedName, field);
                }
                map.put(className, fieldMap);
            }
        }
        return map;
    }

    private ControllerDTO queryPathDetailByOptions(String serviceName, JsonNode pathNode, List<ControllerDTO> targetControllers, String operationId,
                                                   Map<String, String> dtoMap, String basePath) {
        Iterator<String> urlIterator = pathNode.fieldNames();
        while (urlIterator.hasNext()) {
            String url = urlIterator.next();
            JsonNode methodNode = pathNode.get(url);
            Iterator<String> methodIterator = methodNode.fieldNames();
            while (methodIterator.hasNext()) {
                String method = methodIterator.next();
                JsonNode pathDetailNode = methodNode.get(method);
                String pathOperationId = pathDetailNode.get("operationId").asText();
                if (operationId.equals(pathOperationId)) {
                    processPathDetail(serviceName, targetControllers, dtoMap, url, methodNode, method, basePath);
                }
            }
        }
        return targetControllers.get(0);
    }

    private void processPaths(String serviceName, JsonNode pathNode, List<ControllerDTO> controllers, Map<String, String> dtoMap, String basePath) {
        Iterator<String> urlIterator = pathNode.fieldNames();
        while (urlIterator.hasNext()) {
            String url = urlIterator.next();
            JsonNode methodNode = pathNode.get(url);
            Iterator<String> methodIterator = methodNode.fieldNames();
            while (methodIterator.hasNext()) {
                String method = methodIterator.next();
                processPathDetail(serviceName, controllers, dtoMap, url, methodNode, method, basePath);
            }
        }
    }

    private void processPathDetail(String serviceName, List<ControllerDTO> controllers, Map<String, String> dtoMap,
                                   String url, JsonNode methodNode, String method, String basePath) {
        PathDTO path = new PathDTO();
        path.setBasePath(basePath);
        path.setUrl(url);
        path.setMethod(method);
        JsonNode jsonNode = methodNode.findValue(method);
        JsonNode tagNode = jsonNode.get("tags");

        path.setInnerInterface(false);
        setCodeOfPathIfExists(serviceName, path, jsonNode.get(DESCRIPTION), tagNode);

        for (int i = 0; i < tagNode.size(); i++) {
            String tag = tagNode.get(i).asText();
            controllers.forEach(c -> {
                List<PathDTO> paths = c.getPaths();
                if (tag.equals(c.getName())) {
                    path.setRefController(c.getName());
                    paths.add(path);
                }
            });
        }
        path.setRemark(Optional.ofNullable(jsonNode.get("summary")).map(JsonNode::asText).orElse(null));
        path.setDescription(Optional.ofNullable(jsonNode.get(DESCRIPTION)).map(JsonNode::asText).orElse(null));
        path.setOperationId(Optional.ofNullable(jsonNode.get("operationId")).map(JsonNode::asText).orElse(null));
        processConsumes(path, jsonNode);
        processProduces(path, jsonNode);
        processResponses(path, jsonNode, dtoMap);
        processParameters(path, jsonNode, dtoMap);
    }

    private void processResponses(PathDTO path, JsonNode jsonNode, Map<String, String> controllerMaps) {
        JsonNode responseNode = jsonNode.get("responses");
        List<ResponseDTO> responses = new ArrayList<>();
        Iterator<String> responseIterator = responseNode.fieldNames();
        while (responseIterator.hasNext()) {
            String status = responseIterator.next();
            JsonNode node = responseNode.get(status);
            ResponseDTO response = new ResponseDTO();
            response.setHttpStatus(status);
            response.setDescription(node.get(DESCRIPTION).asText());
            JsonNode schemaNode = node.get("schema");
            if (schemaNode != null) {
                JsonNode refNode = schemaNode.get("$ref");
                if (refNode != null) {
                    for (Map.Entry<String, String> entry : controllerMaps.entrySet()) {
                        String className = subString4ClassName(refNode.asText());
                        if (className.equals(entry.getKey())) {
                            response.setBody(entry.getValue());
                        }
                    }
                } else {
                    String type = Optional.ofNullable(schemaNode.get("type")).map(JsonNode::asText).orElse(null);
                    String ref = Optional.ofNullable(schemaNode.get("items"))
                            .map(itemNode ->
                                    Optional.ofNullable(itemNode.get("$ref"))
                                            .map(JsonNode::asText)
                                            .orElse(null))
                            .orElse(null);
                    if (ref != null) {
                        String body = "";
                        for (Map.Entry<String, String> entry : controllerMaps.entrySet()) {
                            String className = subString4ClassName(ref);
                            if (className.equals(entry.getKey())) {
                                body = entry.getValue();
                            }
                        }
                        StringBuilder sb = arrayTypeAppendBrackets(type, body);
                        //给array前面的注释加上缩进，即满足\n//\\S+\n的注释
                        response.setBody(sb.toString());
                    } else {
                        if ("object".equals(type)) {
                            response.setBody("{}");
                        } else {
                            response.setBody(type);
                        }
                    }
                }
            }
            responses.add(response);
        }
        path.setResponses(responses);
    }

    /**
     * set the code field of the instance of {@link PathDTO} if the extraDataNode parameter
     * is not null
     *
     * @param serviceName   the name of the service
     * @param path          the dto
     * @param extraDataNode the extra data node
     * @param tagNode       the tag node
     */
    private void setCodeOfPathIfExists(String serviceName, PathDTO path, JsonNode extraDataNode, JsonNode tagNode) {
        if (extraDataNode != null) {
            try {
                SwaggerExtraData extraData;
                String resourceCode = null;
                for (int i = 0; i < tagNode.size(); i++) {
                    String tag = tagNode.get(i).asText();
                    if (tag.endsWith("-controller")) {
                        resourceCode = tag.substring(0, tag.length() - "-controller".length());
                    }
                }
                extraData = new ObjectMapper().readValue(extraDataNode.asText(), SwaggerExtraData.class);
                PermissionData permission = extraData.getPermission();
                String action = permission.getAction();
                path.setInnerInterface(permission.isPermissionWithin());
                path.setCode(String.format("%s-service.%s.%s", serviceName, resourceCode, action));
            } catch (IOException e) {
                logger.info("extraData read failed.", e);
            }
        }
    }

    private void processConsumes(PathDTO path, JsonNode jsonNode) {
        JsonNode consumeNode = jsonNode.get("consumes");
        List<String> consumes = new ArrayList<>();
        for (int i = 0; i < consumeNode.size(); i++) {
            consumes.add(consumeNode.get(i).asText());
        }
        path.setConsumes(consumes);
    }

    private void processProduces(PathDTO path, JsonNode jsonNode) {
        JsonNode produceNode = jsonNode.get("produces");
        List<String> produces = new ArrayList<>();
        for (int i = 0; i < produceNode.size(); i++) {
            produces.add(produceNode.get(i).asText());
        }
        path.setProduces(produces);
    }

    private void processParameters(PathDTO path, JsonNode jsonNode, Map<String, String> controllerMaps) {
        JsonNode parameterNode = jsonNode.get("parameters");
        List<ParameterDTO> parameters = new ArrayList<>();
        if (parameterNode != null) {
            for (int i = 0; i < parameterNode.size(); i++) {
                try {
                    ParameterDTO parameter = objectMapper.treeToValue(parameterNode.get(i), ParameterDTO.class);
                    SchemaDTO schema = parameter.getSchema();
                    if ("body".equals(parameter.getIn()) && schema != null) {
                        String ref = schema.getRef();
                        if (ref != null) {
                            for (Map.Entry<String, String> entry : controllerMaps.entrySet()) {
                                String className = subString4ClassName(ref);
                                if (className.equals(entry.getKey())) {
                                    String body = entry.getValue();
                                    parameter.setBody(body);
                                }
                            }
                        } else {
                            String type = schema.getType();
                            String itemRef = Optional.ofNullable(schema.getItems()).map(m -> m.get("$ref")).orElse(null);
                            if (itemRef != null) {
                                String body = "";
                                for (Map.Entry<String, String> entry : controllerMaps.entrySet()) {
                                    String className = subString4ClassName(itemRef);
                                    if (className.equals(entry.getKey())) {
                                        body = entry.getValue();
                                    }
                                }
                                StringBuilder sb = arrayTypeAppendBrackets(type, body);
                                parameter.setBody(sb.toString());
                            } else {
                                if (!"object".equals(type)) {
                                    parameter.setBody(type);
                                } else {
                                    Map<String, String> map = schema.getAdditionalProperties();
                                    if (map != null && "array".equals(map.get("type"))) {
                                        parameter.setBody("[{}]");
                                    } else {
                                        parameter.setBody("{}");
                                    }
                                }
                            }
                        }
                    }
                    parameters.add(parameter);
                } catch (JsonProcessingException e) {
                    logger.info("jsonNode to parameterDTO failed, exception: {}", e.getMessage());
                }
            }
        }
        path.setParameters(parameters);
    }

    private StringBuilder arrayTypeAppendBrackets(String type, String body) {
        StringBuilder sb = new StringBuilder();
        if ("array".equals(type)) {
            sb.append("[\n");
            sb.append(body);
            sb.append("\n]");
        } else {
            sb.append(body);
        }
        return sb;
    }
}
