#!/usr/bin/bash

# �����̳� ������ �� �̰͵� csws���� ����Ǿ�� �Ѵ�.
SendPublickey()
{
    local hostName=$1 # ����� ���� �̸�
    local hostIp=$2 # ����� ���� IP �ּ�
    local conName=$3
    local keyName=$4

    ssh -o StrictHostKeyChecking=no $hostName@$hostIp "mkdir Keys"
    scp ~/Keys/$hostName/$keyName.pub $hostName@$hostIp:~/Keys/ 
    rm ~/Keys/$hostName/$keyName.pub
    ssh $hostName@$hostIp "sh H_SendPublickey.sh $keyName $conName"
}

SendPublickey $1 $2 $3 $4 $5