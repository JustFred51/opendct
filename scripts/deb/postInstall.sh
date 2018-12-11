#!/bin/sh

getent group opendct >/dev/null || groupadd -r opendct
getent passwd opendct >/dev/null || useradd -r -g opendct -d /opt/opendct -s /bin/bash -c "OpenDCT Service Account" opendct

if test ! -e /var/log/opendct; then
    mkdir -p /var/log/opendct
fi

if test ! -e /var/run/opendct; then
    mkdir -p /var/run/opendct
fi

chown opendct:opendct /var/log/opendct
chown opendct:opendct /var/run/opendct
chown opendct:opendct /opt/opendct

##  Setup init.d daemon configuration if system is not running systemd
##  The systemd service is setup by default on debian package install
if test ! -e /run/systemd/system; then
    ln -fs /opt/opendct/service /etc/init.d/opendct
    chmod 755 /etc/init.d/opendct
    update-rc.d -f opendct defaults
fi

##### 0.4.18 Upgrade START#####
if test ! -e /etc/opendct/conf; then
    mkdir -p /etc/opendct/conf
fi

chown opendct:opendct /etc/opendct/conf

if test -e /opt/opendct/conf; then
    echo ""
    echo "Copying the files in the directory /opt/opendct/conf/* to /etc/opendct/conf/..."

    # -n option is used in case the user already moved or was confused about the changes and renames
    # the file we're checking back. This way we don't overwrite settings actually in use.
    cp -rvn /opt/opendct/conf/* /etc/opendct/conf/
    mv /opt/opendct/conf /opt/opendct/conf.moved

    # There is also a problem that is the reason for this change whereby the opendct.properties file
    # might be removed/replaced as a part of the upgrade. This uses the last backup copy to attempt
    # to fix the problem.
    if test ! -e /opt/opendct/conf.moved/opendct.properties && test -e /opt/opendct/conf.moved/opendct.properties.backup; then
        echo "Attempting to restore /etc/opendct/conf/opendct.properties from backup..."
        cp -vf /opt/opendct/conf.moved/opendct.properties.backup /etc/opendct/conf/opendct.properties
    fi

    echo ""
    echo "Successfully copied the files."
    echo "All configuration data is now located in the folder /etc/opendct/conf"
    echo ""
    echo "You can safely delete the old configuration folder /opt/opendct/conf.moved"
    echo ""
fi
##### 0.4.18 Upgrade END #####

echo ""
echo "To use the provided ufw rules type:"
echo "/opt/opendct/enable-ufw-ports"
echo ""
echo "To delete the provided ufw rules type:"
echo "/opt/opendct/disable-ufw-ports"
echo ""
echo "To start the OpenDCT service type:"
echo "Ubuntu 14.04:"
echo "sudo service opendct start"
echo "Ubuntu 16.04 & 18.04:"
echo "sudo systemctl enable opendct.service"
echo "sudo systemctl start opendct.service"

exit 0