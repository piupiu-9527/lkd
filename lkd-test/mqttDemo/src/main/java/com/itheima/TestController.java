package com.itheima;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {


    /**
     * 发布消息
     * @throws MqttException
     */
    @GetMapping("/publish")
    public void publish() throws MqttException {

        MqttClientPersistence persistence = new MemoryPersistence();;//内存持久化
        MqttClient client = new MqttClient("tcp://192.168.200.128:1883", "abc", persistence);
        //连接选项中定义用户名密码和其它配置
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);//参数为true表示清除缓存，也就是非持久化订阅者，这个时候只要参数设为true，一定是非持久化订阅者。而参数设为false时，表示服务器保留客户端的连接记录
        options.setAutomaticReconnect(true);//是否自动重连
        options.setConnectionTimeout(30);//连接超时时间  秒
        options.setKeepAliveInterval(10);//连接保持检查周期  秒
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1); //版本
        client.connect(options);//连接
        client.publish("topic", "发送内容".getBytes(), 2, false);

    }


    /**
     * 订阅消息
     * @throws MqttException
     */
    @GetMapping("/subscribe")
    public void subscribe() throws MqttException {

        MqttClientPersistence persistence = new MemoryPersistence();;//内存持久化
        MqttClient client = new MqttClient("tcp://192.168.200.128:1883", "xyz", persistence);

        //连接选项中定义用户名密码和其它配置
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);//参数为true表示清除缓存，也就是非持久化订阅者，这个时候只要参数设为true，一定是非持久化订阅者。而参数设为false时，表示服务器保留客户端的连接记录
        options.setAutomaticReconnect(true);//是否自动重连
        options.setConnectionTimeout(30);//连接超时时间  秒
        options.setKeepAliveInterval(10);//连接保持检查周期  秒
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1); //版本

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectionLost(Throwable throwable) {
                System.out.println("连接丢失！");
            }

            @Override
            public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
                System.out.println( "接收到消息  topic:" +s+"  id:"+mqttMessage.getId() +" message:"+ mqttMessage.toString());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

            }

            @Override
            public void connectComplete(boolean b, String s) {
                System.out.println("连接成功！");
            }
        });
        client.connect(options);//连接
        client.subscribe("test");  //订阅主题

    }


}
